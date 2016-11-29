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
package org.apache.activemq.artemis.utils.uri;

import java.beans.PropertyDescriptor;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.commons.beanutils.Converter;

public abstract class URISchema<T, P> {

   public abstract String getSchemaName();

   public T newObject(URI uri, P param) throws Exception {
      return newObject(uri, null, param);
   }

   public void populateObject(URI uri, T bean) throws Exception {
      setData(uri, bean, parseQuery(uri.getQuery(), null));
   }

   public URI newURI(T bean) throws Exception {
      return internalNewURI(bean);
   }

   private URIFactory<T, P> parentFactory;

   void setFactory(URIFactory<T, P> factory) {
      this.parentFactory = factory;
   }

   protected URIFactory<T, P> getFactory() {
      return parentFactory;
   }

   protected String getHost(URI uri) {
      URI defaultFactory = getDefaultURI();
      if (defaultFactory != null && uri.getHost() == null && defaultFactory.getScheme().equals(uri.getScheme())) {
         uri = defaultFactory;
      }
      return uri.getHost();
   }

   protected URI getDefaultURI() {
      URIFactory<T, P> factory = getFactory();
      if (factory == null) {
         return null;
      }
      else {
         return factory.getDefaultURI();
      }
   }

   protected int getPort(URI uri) {
      URI defaultFactory = getDefaultURI();
      if (defaultFactory != null && uri.getPort() < 0 && defaultFactory.getScheme().equals(uri.getScheme())) {
         uri = defaultFactory;
      }
      return uri.getPort();
   }

   /**
    * It will create a new Object for the URI selected schema.
    * the propertyOverrides is used to replace whatever was defined on the URL string
    *
    * @param uri               The URI
    * @param propertyOverrides used to replace whatever was defined on the URL string
    * @return new Object
    * @throws Exception On error
    */
   public T newObject(URI uri, Map<String, String> propertyOverrides, P param) throws Exception {
      return internalNewObject(uri, parseQuery(uri.getQuery(), propertyOverrides), param);
   }

   protected abstract T internalNewObject(URI uri, Map<String, String> query, P param) throws Exception;

   /** This is the default implementation.
    *  Sub classes are should provide a proper implementation for their schemas. */
   protected URI internalNewURI(T bean) throws Exception {
      String query = URISchema.getData(null, bean);

      return new URI(getSchemaName(),
          null,
          "//", query, null);

   }

   private static final BeanUtilsBean beanUtils = new BeanUtilsBean();

   public static void registerConverter(Converter converter, Class type) {
      synchronized (beanUtils) {
         beanUtils.getConvertUtils().register(converter, type);
      }
   }

   public static String decodeURI(String value) throws UnsupportedEncodingException {
      return URLDecoder.decode(value, "UTF-8");
   }

   public static String encodeURI(String value) throws UnsupportedEncodingException {
      return URLEncoder.encode(value, "UTF-8");
   }

   static {
      // This is to customize the BeanUtils to use Fluent Proeprties as well
      beanUtils.getPropertyUtils().addBeanIntrospector(new FluentPropertyBeanIntrospectorWithIgnores());
   }

   public static Map<String, String> parseQuery(String uri,
                                                Map<String, String> propertyOverrides) throws URISyntaxException {
      try {
         Map<String, String> rc = new HashMap<>();
         if (uri != null && !uri.isEmpty()) {
            String[] parameters = uri.split("&");
            for (int i = 0; i < parameters.length; i++) {
               int p = parameters[i].indexOf("=");
               if (p >= 0) {
                  String name = decodeURI(parameters[i].substring(0, p));
                  String value = decodeURI(parameters[i].substring(p + 1));
                  rc.put(name, value);
               }
               else {
                  if (!parameters[i].trim().isEmpty()) {
                     rc.put(parameters[i], null);
                  }
               }
            }
         }

         if (propertyOverrides != null) {
            for (Map.Entry<String, String> entry : propertyOverrides.entrySet()) {
               rc.put(entry.getKey(), entry.getValue());
            }
         }
         return rc;
      }
      catch (UnsupportedEncodingException e) {
         throw (URISyntaxException) new URISyntaxException(e.toString(), "Invalid encoding").initCause(e);
      }
   }

   protected String printQuery(Map<String, String> query) {
      StringBuffer buffer = new StringBuffer();
      for (Map.Entry<String, String> entry : query.entrySet()) {
         buffer.append(entry.getKey() + "=" + entry.getValue());
         buffer.append("\n");
      }

      return buffer.toString();
   }

   protected static <P> P copyData(P source, P target) throws Exception {
      synchronized (beanUtils) {
         beanUtils.copyProperties(source, target);
      }
      return target;
   }

   protected static <P> P setData(URI uri, P obj, Map<String, String> query) throws Exception {
      synchronized (beanUtils) {
         beanUtils.setProperty(obj, "host", uri.getHost());
         beanUtils.setProperty(obj, "port", uri.getPort());
         beanUtils.setProperty(obj, "userInfo", uri.getUserInfo());
         beanUtils.populate(obj, query);
      }
      return obj;
   }

   public static void setData(URI uri,
                              HashMap<String, Object> properties,
                              Set<String> allowableProperties,
                              Map<String, String> query) {
      if (allowableProperties.contains("host")) {
         properties.put("host", "" + uri.getHost());
      }
      if (allowableProperties.contains("port")) {
         properties.put("port", "" + uri.getPort());
      }
      if (allowableProperties.contains("userInfo")) {
         properties.put("userInfo", "" + uri.getUserInfo());
      }
      for (Map.Entry<String, String> entry : query.entrySet()) {
         if (allowableProperties.contains(entry.getKey())) {
            properties.put(entry.getKey(), entry.getValue());
         }
      }
   }

   public static String getData(List<String> ignored, Object... beans) throws Exception {
      StringBuilder sb = new StringBuilder();
      boolean empty = true;
      synchronized (beanUtils) {
         for (Object bean : beans) {
            if (bean != null) {
               PropertyDescriptor[] descriptors = beanUtils.getPropertyUtils().getPropertyDescriptors(bean);
               for (PropertyDescriptor descriptor : descriptors) {
                  if (descriptor.getReadMethod() != null && isWriteable(descriptor, ignored)) {
                     String value = beanUtils.getProperty(bean, descriptor.getName());
                     if (value != null) {
                        if (!empty) {
                           sb.append("&");
                        }
                        empty = false;
                        sb.append(descriptor.getName()).append("=").append(encodeURI(value));
                     }
                  }
               }
            }
         }
      }
      return sb.toString();
   }

   private static boolean isWriteable(PropertyDescriptor descriptor, List<String> ignored) {
      if (ignored != null && ignored.contains(descriptor.getName())) {
         return false;
      }
      Class<?> type = descriptor.getPropertyType();
      return (type == Double.class) ||
         (type == double.class) ||
         (type == Long.class) ||
         (type == long.class) ||
         (type == Integer.class) ||
         (type == int.class) ||
         (type == Float.class) ||
         (type == float.class) ||
         (type == Boolean.class) ||
         (type == boolean.class) ||
         (type == String.class);
   }
}
