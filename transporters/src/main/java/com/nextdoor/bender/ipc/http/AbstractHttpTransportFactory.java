/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 * Copyright 2017 Nextdoor.com, Inc
 *
 */

package com.nextdoor.bender.ipc.http;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.http.config.SocketConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;

import com.nextdoor.bender.config.AbstractConfig;
import com.nextdoor.bender.ipc.TransportBuffer;
import com.nextdoor.bender.ipc.TransportException;
import com.nextdoor.bender.ipc.TransportFactory;
import com.nextdoor.bender.ipc.TransportFactoryInitException;
import com.nextdoor.bender.ipc.TransportSerializer;
import com.nextdoor.bender.ipc.UnpartitionedTransport;
import com.nextdoor.bender.ipc.generic.GenericTransportBuffer;

public abstract class AbstractHttpTransportFactory implements TransportFactory {
  protected AbstractHttpTransportConfig config;
  protected TransportSerializer serializer;
  protected CloseableHttpClient client;

  abstract protected String getPath();

  abstract protected TransportSerializer getSerializer();

  @Override
  public void setConf(AbstractConfig config) {
    this.config = (AbstractHttpTransportConfig) config;
    this.serializer = getSerializer();

    this.client = getClient(this.config.isUseSSL(), this.getUrl(), this.getHeaders(),
        this.config.getTimeout());
  }

  protected String getUrl() {
    String url = "";
    if (this.config.isUseSSL()) {
      url += "https://";
    } else {
      url += "http://";
    }
    url += this.config.getHostname() + ":" + this.config.getPort() + this.getPath();

    return url;
  }

  protected Map<String, String> getHeaders() {
    return this.config.getHttpHeaders();
  }

  /**
   * There isn't an easy way in java to trust non-self signed certs. Just allow all until java
   * KeyStore functionality is added to Bender.
   *
   * @return a context that trusts all SSL certs
   */
  private SSLContext getSSLContext() {
    /*
     * Create SSLContext and TrustManager that will trust all SSL certs.
     *
     * Copy pasta from http://stackoverflow.com/a/4837230
     */
    TrustManager tm = new X509TrustManager() {
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {}

      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {}

      public X509Certificate[] getAcceptedIssuers() {
        return null;
      }
    };

    SSLContext ctx;
    try {
      ctx = SSLContext.getInstance("TLS");
    } catch (NoSuchAlgorithmException e) {
      throw new TransportFactoryInitException("JVM does not have proper libraries for TSL");
    }

    try {
      ctx.init(null, new TrustManager[] {tm}, new java.security.SecureRandom());
    } catch (KeyManagementException e) {
      throw new TransportFactoryInitException("Unable to init SSLContext with TrustManager", e);
    }
    return ctx;
  }

  protected HttpClientBuilder getClientBuilder(boolean useSSL, String url,
      Map<String, String> stringHeaders, int socketTimeout) {

    HttpClientBuilder cb = HttpClientBuilder.create();

    /*
     * Setup SSL
     */
    if (useSSL) {
      /*
       * All trusting SSL context
       */
      try {
        cb.setSSLContext(getSSLContext());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      /*
       * All trusting hostname verifier
       */
      cb.setSSLHostnameVerifier(new HostnameVerifier() {
        public boolean verify(String s, SSLSession sslSession) {
          return true;
        }
      });
    }

    /*
     * Add default headers
     */
    ArrayList<BasicHeader> headers = new ArrayList<BasicHeader>(stringHeaders.size());
    stringHeaders.forEach((k, v) -> headers.add(new BasicHeader(k, v)));
    cb.setDefaultHeaders(headers);

    /*
     * Set socket timeout and transport threads
     */
    SocketConfig sc = SocketConfig.custom().setSoTimeout(socketTimeout).build();
    cb.setDefaultSocketConfig(sc);
    cb.setMaxConnPerRoute(this.config.getThreads());
    cb.setMaxConnTotal(this.config.getThreads());

    return cb;
  }

  protected CloseableHttpClient getClient(boolean useSSL, String url,
      Map<String, String> stringHeaders, int socketTimeout) {
    return getClientBuilder(useSSL, url, stringHeaders, socketTimeout).build();
  }

  @Override
  public int getMaxThreads() {
    return this.config.getThreads();
  }

  @Override
  public void close() {}

  @Override
  public UnpartitionedTransport newInstance() throws TransportFactoryInitException {
    return new HttpTransport(this.client, this.getUrl(), this.config.isUseGzip(),
        this.config.getRetryCount(), this.config.getRetryDelay());
  }

  @Override
  public TransportBuffer newTransportBuffer() throws TransportException {
    try {
      return new GenericTransportBuffer(this.config.getBatchSize(), this.config.isUseGzip(),
          this.serializer);
    } catch (IOException e) {
      throw new TransportException("error creating GenericTransportBuffer", e);
    }
  }
}
