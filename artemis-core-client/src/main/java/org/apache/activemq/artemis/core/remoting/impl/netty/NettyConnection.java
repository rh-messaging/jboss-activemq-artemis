/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.remoting.impl.netty;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.ssl.SslHandler;
import org.apache.activemq.artemis.api.core.ActiveMQBuffer;
import org.apache.activemq.artemis.api.core.ActiveMQInterruptedException;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.core.buffers.impl.ChannelBufferWrapper;
import org.apache.activemq.artemis.core.client.ActiveMQClientLogger;
import org.apache.activemq.artemis.core.security.ActiveMQPrincipal;
import org.apache.activemq.artemis.spi.core.protocol.RemotingConnection;
import org.apache.activemq.artemis.spi.core.remoting.BaseConnectionLifeCycleListener;
import org.apache.activemq.artemis.spi.core.remoting.Connection;
import org.apache.activemq.artemis.spi.core.remoting.ReadyListener;
import org.apache.activemq.artemis.utils.IPV6Util;

public class NettyConnection implements Connection {

   private static final int DEFAULT_MTU_BYTES = Integer.getInteger("io.netty.mtu", 1460);
   //backpressure on unbatched writes is enabled by default
   private static final boolean ENABLED_RELAXED_BACK_PRESSURE = !Boolean.getBoolean("io.netty.disable.backpressure");
   //it is the limit while waiting the data to be flushed and alerting (if in trace mode) the event
   private static final long DEFAULT_BACK_PRESSURE_WAIT_MILLIS = Long.getLong("io.netty.backpressure.millis", 1_000L);
   //if not specified the default batch size will be equal to the ChannelConfig::writeBufferHighWaterMark
   private static final int DEFAULT_BATCH_BYTES = Integer.getInteger("io.netty.batch.bytes", Integer.MAX_VALUE);
   private static final int DEFAULT_WAIT_MILLIS = 10_000;

   protected final Channel channel;
   private final BaseConnectionLifeCycleListener listener;
   private final boolean directDeliver;
   private final Map<String, Object> configuration;
   /**
    * if {@link #isWritable(ReadyListener)} returns false, we add a callback
    * here for when the connection (or Netty Channel) becomes available again.
    */
   private final List<ReadyListener> readyListeners = new ArrayList<>();
   private final ThreadLocal<ArrayList<ReadyListener>> localListenersPool = ThreadLocal.withInitial(ArrayList::new);

   private final boolean batchingEnabled;
   private final int writeBufferHighWaterMark;
   private final int batchLimit;

   private final AtomicLong pendingWritesOnEventLoopView = new AtomicLong();
   private long pendingWritesOnEventLoop = 0;

   private boolean closed;
   private RemotingConnection protocolConnection;

   private boolean ready = true;

   public NettyConnection(final Map<String, Object> configuration,
                          final Channel channel,
                          final BaseConnectionLifeCycleListener listener,
                          boolean batchingEnabled,
                          boolean directDeliver) {
      this.configuration = configuration;

      this.channel = channel;

      this.listener = listener;

      this.directDeliver = directDeliver;

      this.batchingEnabled = batchingEnabled;

      this.writeBufferHighWaterMark = this.channel.config().getWriteBufferHighWaterMark();

      this.batchLimit = batchingEnabled ? Math.min(this.writeBufferHighWaterMark, DEFAULT_BATCH_BYTES) : 0;
   }

   private static void waitFor(ChannelPromise promise, long millis) {
      try {
         final boolean completed = promise.await(millis);
         if (!completed) {
            ActiveMQClientLogger.LOGGER.timeoutFlushingPacket();
         }
      } catch (InterruptedException e) {
         throw new ActiveMQInterruptedException(e);
      }
   }

   /**
    * Returns an estimation of the current size of the write buffer in the channel.
    * To obtain a more precise value is necessary to use the unsafe API of the channel to
    * call the {@link io.netty.channel.ChannelOutboundBuffer#totalPendingWriteBytes()}.
    * Anyway, both these values are subject to concurrent modifications.
    */
   private static int batchBufferSize(Channel channel, int writeBufferHighWaterMark) {
      //Channel::bytesBeforeUnwritable is performing a volatile load
      //this is the reason why writeBufferHighWaterMark is passed as an argument
      final int bytesBeforeUnwritable = (int) channel.bytesBeforeUnwritable();
      assert bytesBeforeUnwritable >= 0;
      final int writtenBytes = writeBufferHighWaterMark - bytesBeforeUnwritable;
      assert writtenBytes >= 0;
      return writtenBytes;
   }

   /**
    * When batching is not enabled, it tries to back-pressure the caller thread.
    * The back-pressure provided is not before the writeAndFlush request, buf after it: too many threads that are not
    * using {@link Channel#isWritable} to know when push unbatched data will risk to cause OOM due to the enqueue of each own {@link Channel#writeAndFlush} requests.
    * Trying to provide back-pressure before the {@link Channel#writeAndFlush} request could work, but in certain scenarios it will block {@link Channel#isWritable} to be true.
    */
   private static ChannelFuture backPressuredWriteAndFlush(final ByteBuf bytes,
                                                           final int readableBytes,
                                                           final Channel channel,
                                                           final ChannelPromise promise) {
      final ChannelFuture future;
      if (!channel.isWritable()) {
         final ChannelPromise channelPromise = promise.isVoid() ? channel.newPromise() : promise;
         future = channel.writeAndFlush(bytes, channelPromise);
         //is the channel is not writable wait the current request to be flushed, providing backpressuring on the caller thread
         if (!channel.isWritable() && !future.awaitUninterruptibly(DEFAULT_BACK_PRESSURE_WAIT_MILLIS)) {
            if (ActiveMQClientLogger.LOGGER.isTraceEnabled()) {
               ActiveMQClientLogger.LOGGER.trace(Thread.currentThread().getName() + " - [" + channel.id() + "] unable to flush " + readableBytes + " bytes in " + DEFAULT_BACK_PRESSURE_WAIT_MILLIS + " ms");
            }
         }
      } else {
         future = channel.writeAndFlush(bytes, promise);
      }
      return future;
   }

   public final int batchBufferCapacity() {
      return this.batchLimit;
   }

   public final boolean isBatchingEnabled() {
      return batchingEnabled;
   }

   public final int pendingWritesOnChannel() {
      return batchBufferSize(this.channel, this.writeBufferHighWaterMark);
   }

   public final long pendingWritesOnEventLoop() {
      return pendingWritesOnEventLoopView.get();
   }

   public Channel getNettyChannel() {
      return channel;
   }

   @Override
   public final void setAutoRead(boolean autoRead) {
      channel.config().setAutoRead(autoRead);
   }

   @Override
   public final boolean isWritable(ReadyListener callback) {
      synchronized (readyListeners) {
         if (!ready) {
            readyListeners.add(callback);
         }

         return ready;
      }
   }

   @Override
   public final void fireReady(final boolean ready) {
      final ArrayList<ReadyListener> readyToCall = localListenersPool.get();
      synchronized (readyListeners) {
         this.ready = ready;

         if (ready) {
            final int size = this.readyListeners.size();
            readyToCall.ensureCapacity(size);
            try {
               for (int i = 0; i < size; i++) {
                  final ReadyListener readyListener = readyListeners.get(i);
                  if (readyListener == null) {
                     break;
                  }
                  readyToCall.add(readyListener);
               }
            } finally {
               readyListeners.clear();
            }
         }
      }
      try {
         final int size = readyToCall.size();
         for (int i = 0; i < size; i++) {
            try {
               final ReadyListener readyListener = readyToCall.get(i);
               readyListener.readyForWriting();
            } catch (Throwable logOnly) {
               ActiveMQClientLogger.LOGGER.warn(logOnly.getMessage(), logOnly);
            }
         }
      } finally {
         readyToCall.clear();
      }
   }

   @Override
   public final void forceClose() {
      if (channel != null) {
         try {
            channel.close();
         } catch (Throwable e) {
            ActiveMQClientLogger.LOGGER.warn(e.getMessage(), e);
         }
      }
   }

   /**
    * This is exposed so users would have the option to look at any data through interceptors
    *
    * @return
    */
   public final Channel getChannel() {
      return channel;
   }

   @Override
   public final RemotingConnection getProtocolConnection() {
      return protocolConnection;
   }

   @Override
   public final void setProtocolConnection(RemotingConnection protocolConnection) {
      this.protocolConnection = protocolConnection;
   }

   @Override
   public final void close() {
      if (closed) {
         return;
      }
      EventLoop eventLoop = channel.eventLoop();
      boolean inEventLoop = eventLoop.inEventLoop();
      //if we are in an event loop we need to close the channel after the writes have finished
      if (!inEventLoop) {
         final SslHandler sslHandler = (SslHandler) channel.pipeline().get("ssl");
         closeSSLAndChannel(sslHandler, channel, false);
      } else {
         eventLoop.execute(() -> {
            final SslHandler sslHandler = (SslHandler) channel.pipeline().get("ssl");
            closeSSLAndChannel(sslHandler, channel, true);
         });
      }

      closed = true;

      listener.connectionDestroyed(getID());
   }

   @Override
   public ActiveMQBuffer createTransportBuffer(final int size) {
      return createTransportBuffer(size, false);
   }

   @Override
   public final ActiveMQBuffer createTransportBuffer(final int size, boolean pooled) {
      try {
         return new ChannelBufferWrapper(channel.alloc().directBuffer(size), true);
      } catch (OutOfMemoryError oom) {
         if (ActiveMQClientLogger.LOGGER.isTraceEnabled()) {
            final long totalPendingWriteBytes = batchBufferSize(this.channel, this.writeBufferHighWaterMark);
            ActiveMQClientLogger.LOGGER.trace("pendingWrites: [NETTY] -> " + totalPendingWriteBytes + "[EVENT LOOP] -> " + pendingWritesOnEventLoopView.get() + " causes: " + oom.getMessage(), oom);
         }
         throw oom;
      }
   }

   @Override
   public final Object getID() {
      // TODO: Think of it
      return channel.hashCode();
   }

   // This is called periodically to flush the batch buffer
   @Override
   public final void checkFlushBatchBuffer() {
      if (this.batchingEnabled) {
         //perform the flush only if necessary
         final int batchBufferSize = batchBufferSize(this.channel, this.writeBufferHighWaterMark);
         if (batchBufferSize > 0) {
            this.channel.flush();
         }
      }
   }

   @Override
   public final void write(final ActiveMQBuffer buffer) {
      write(buffer, false, false);
   }

   @Override
   public final void write(ActiveMQBuffer buffer, final boolean flush, final boolean batched) {
      write(buffer, flush, batched, null);
   }

   @Override
   public final void write(ActiveMQBuffer buffer,
                           final boolean flush,
                           final boolean batched,
                           final ChannelFutureListener futureListener) {
      //no need to lock because the Netty's channel is thread-safe
      //and the order of write is ensured by the order of the write calls
      final EventLoop eventLoop = channel.eventLoop();
      final boolean inEventLoop = eventLoop.inEventLoop();
      if (!inEventLoop) {
         writeNotInEventLoop(buffer, flush, batched, futureListener);
      } else {
         // OLD COMMENT:
         // create a task which will be picked up by the eventloop and trigger the write.
         // This is mainly needed as this method is triggered by different threads for the same channel.
         // if we not do this we may produce out of order writes.
         // NOTE:
         // the submitted task does not effect in any way the current written size in the batch
         // until the loop will process it, leading to a longer life for the ActiveMQBuffer buffer!!!
         // To solve it, will be necessary to manually perform the count of the current batch instead of rely on the
         // Channel:Config::writeBufferHighWaterMark value.
         pendingWritesOnEventLoop += buffer.readableBytes();
         pendingWritesOnEventLoopView.lazySet(pendingWritesOnEventLoop);
         eventLoop.execute(() -> {
            pendingWritesOnEventLoop -= buffer.readableBytes();
            pendingWritesOnEventLoopView.lazySet(pendingWritesOnEventLoop);
            writeInEventLoop(buffer, flush, batched, futureListener);
         });
      }
   }

   private void writeNotInEventLoop(ActiveMQBuffer buffer,
                                    final boolean flush,
                                    final boolean batched,
                                    final ChannelFutureListener futureListener) {
      final Channel channel = this.channel;
      final ChannelPromise promise;
      if (flush || (futureListener != null)) {
         promise = channel.newPromise();
      } else {
         promise = channel.voidPromise();
      }
      final ChannelFuture future;
      final ByteBuf bytes = buffer.byteBuf();
      final int readableBytes = bytes.readableBytes();
      assert readableBytes >= 0;
      final int writeBatchSize = this.batchLimit;
      final boolean batchingEnabled = this.batchingEnabled;
      if (batchingEnabled && batched && !flush && readableBytes < writeBatchSize) {
         future = writeBatch(bytes, readableBytes, promise);
      } else {
         //enable relaxed back-pressuring only for "big" writes and only when batching is not enabled -> no background calls on this::checkFlushBatchBuffer
         if (ENABLED_RELAXED_BACK_PRESSURE && !batchingEnabled && readableBytes > DEFAULT_MTU_BYTES) {
            future = backPressuredWriteAndFlush(bytes, readableBytes, channel, promise);
         } else {
            future = channel.writeAndFlush(bytes, promise);
         }
      }
      if (futureListener != null) {
         future.addListener(futureListener);
      }
      if (flush) {
         //NOTE: this code path seems used only on RemotingConnection::disconnect
         waitFor(promise, DEFAULT_WAIT_MILLIS);
      }
   }

   private void writeInEventLoop(ActiveMQBuffer buffer,
                                 final boolean flush,
                                 final boolean batched,
                                 final ChannelFutureListener futureListener) {
      //no need to lock because the Netty's channel is thread-safe
      //and the order of write is ensured by the order of the write calls
      final ChannelPromise promise;
      if (futureListener != null) {
         promise = channel.newPromise();
      } else {
         promise = channel.voidPromise();
      }
      final ChannelFuture future;
      final ByteBuf bytes = buffer.byteBuf();
      final int readableBytes = bytes.readableBytes();
      final int writeBatchSize = this.batchLimit;
      if (this.batchingEnabled && batched && !flush && readableBytes < writeBatchSize) {
         future = writeBatch(bytes, readableBytes, promise);
      } else {
         future = channel.writeAndFlush(bytes, promise);
      }
      if (futureListener != null) {
         future.addListener(futureListener);
      }
   }

   private ChannelFuture writeBatch(final ByteBuf bytes, final int readableBytes, final ChannelPromise promise) {
      final int batchBufferSize = batchBufferSize(channel, this.writeBufferHighWaterMark);
      final int nextBatchSize = batchBufferSize + readableBytes;
      if (nextBatchSize > batchLimit) {
         //request to flush before writing, to create the chance to make the channel writable again
         channel.flush();
         //let netty use its write batching ability
         return channel.write(bytes, promise);
      } else if (nextBatchSize == batchLimit) {
         return channel.writeAndFlush(bytes, promise);
      } else {
         //let netty use its write batching ability
         return channel.write(bytes, promise);
      }
   }

   @Override
   public final String getRemoteAddress() {
      SocketAddress address = channel.remoteAddress();
      if (address == null) {
         return null;
      }
      return address.toString();
   }

   @Override
   public final String getLocalAddress() {
      SocketAddress address = channel.localAddress();
      if (address == null) {
         return null;
      }
      return "tcp://" + IPV6Util.encloseHost(address.toString());
   }

   public final boolean isDirectDeliver() {
      return directDeliver;
   }

   //never allow this
   @Override
   public final ActiveMQPrincipal getDefaultActiveMQPrincipal() {
      return null;
   }

   @Override
   public final TransportConfiguration getConnectorConfig() {
      if (configuration != null) {
         return new TransportConfiguration(NettyConnectorFactory.class.getName(), this.configuration);
      } else {
         return null;
      }
   }

   @Override
   public final boolean isUsingProtocolHandling() {
      return true;
   }

   @Override
   public final String toString() {
      return super.toString() + "[local= " + channel.localAddress() + ", remote=" + channel.remoteAddress() + "]";
   }

   private void closeSSLAndChannel(SslHandler sslHandler, final Channel channel, boolean inEventLoop) {
      checkFlushBatchBuffer();
      if (sslHandler != null) {
         try {
            ChannelFuture sslCloseFuture = sslHandler.close();
            sslCloseFuture.addListener(future -> channel.close());
            if (!inEventLoop && !sslCloseFuture.awaitUninterruptibly(DEFAULT_WAIT_MILLIS)) {
               ActiveMQClientLogger.LOGGER.timeoutClosingSSL();
            }
         } catch (Throwable t) {
            // ignore
            if (ActiveMQClientLogger.LOGGER.isTraceEnabled()) {
               ActiveMQClientLogger.LOGGER.trace(t.getMessage(), t);
            }
         }
      } else {
         ChannelFuture closeFuture = channel.close();
         if (!inEventLoop && !closeFuture.awaitUninterruptibly(DEFAULT_WAIT_MILLIS)) {
            ActiveMQClientLogger.LOGGER.timeoutClosingNettyChannel();
         }
      }
   }

}
