package com.jinxin.practice;


import com.alibaba.fastjson.JSONObject;
import org.apache.http.Header;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.GzipDecompressingEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 可能需要定制化后才能够在生产环境中使用
 *
 * @author Jinxin
 * created at 2022/1/13 16:36
 **/
public class MyHttpsClient {

    private static final Logger logger = LoggerFactory.getLogger(MyHttpsClient.class);

    private final static String CLIENT_PFX_TYPE = "PKCS12";    // 客户端公钥密钥类型
    private final static String CLIENT_PFX_PATH = "httpsKeys/vendor.my.p12";    //客户端证书路径
    private final static String CLIENT_PFX_PWD = "changeit";    //客户端证书密码

    private final static String SERVER_PFX_TYPE = "jks";  // 服务器端公钥类型
    private final static String SERVER_PFX_PATH = "httpsKeys/ca-server.jks";    //服务器端证书路径
    private final static String SERVER_PFX_PWD = "changeit";    // 服务器端证书密码

    private SSLConnectionSocketFactory sslFactory;
    private CloseableHttpClient client;
    private CloseableHttpAsyncClient asyncClient;
    private RequestConfig requestConfig;
    private int retryAsyncCounter = 0;


    /**
     * 发送异步的 Get 请求
     *
     * @param url            请求 Url
     * @param body           请求体
     * @param responseAction 请求成功回执函数
     */
    public void post(String url, String body, Consumer<? super HttpResponse> responseAction) {
        post(null, url, body, responseAction, null);
    }


    /**
     * 发送异步的 Get 请求
     *
     * @param url            请求 Url
     * @param responseAction 请求成功回执函数
     */
    public void get(String url, Consumer<? super HttpResponse> responseAction) {
        get(null, url, responseAction, null);
    }

    /**
     * 发送异步的 Get 请求
     *
     * @param url            请求 Url
     * @param responseAction 请求成功回执函数
     * @param errorAction    请求失败回执函数
     */
    public void get(String url, Consumer<? super HttpResponse> responseAction, BiConsumer<? super HttpUriRequest, ? super Exception> errorAction) {
        get(null, url, responseAction, errorAction);
    }

    /**
     * 发送异步的 Get 请求
     *
     * @param headers        请求头
     * @param url            请求 Url
     * @param responseAction 请求成功回执函数
     * @param errorAction    请求失败回执函数
     */
    public void get(Map<String, String> headers, String url, Consumer<? super HttpResponse> responseAction, BiConsumer<? super HttpUriRequest, ? super Exception> errorAction) {
        HttpGet get = new HttpGet(url);
        if (headers != null) {
            headers.forEach(get::setHeader);
        }
        executeAsync(get, responseAction, errorAction);
    }

    /**
     * 发送异步的 Post 请求
     *
     * @param url            请求 Url
     * @param body           请求内容
     * @param responseAction 请求回执函数
     */
    public void post(String url, String body, Consumer<? super HttpResponse> responseAction, BiConsumer<? super HttpUriRequest, ? super Exception> errorAction) {
        post(null, url, body, responseAction, errorAction);
    }

    /**
     * 发送异步的 Post 请求
     *
     * @param headers        请求头
     * @param url            请求 Url
     * @param body           请求内容
     * @param responseAction 请求回执函数
     * @param errorAction    失败回执函数
     */
    public void post(Map<String, String> headers, String url, String body, Consumer<? super HttpResponse> responseAction, BiConsumer<? super HttpUriRequest, ? super Exception> errorAction) {
        if (headers == null) {
            headers = new HashMap<>();
            headers.put("content-type", "application/json; charset=UTF-8");
        }

        HttpPost post = new HttpPost(url);
        for (String key : headers.keySet()) {
            post.setHeader(key, headers.get(key));
        }
        try {
            post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
            executeAsync(post, responseAction, errorAction);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    /**
     * 发送阻塞的 Get 请求
     *
     * @param headers 请求头
     * @param url     请求 Url
     * @return int 状态码
     */
    public int getCode(Map<String, String> headers, String url) {
        int result = 500;
        try (CloseableHttpResponse response = get(headers, url)) {
            result = response.getStatusLine().getStatusCode();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return result;
    }

    /**
     * 发送阻塞的 Post 请求
     *
     * @param headers 请求头
     * @param url     请求 Url
     * @param body    请求内容
     * @return int 状态码
     */
    public int postCode(Map<String, String> headers, String url, String body) {
        int result = 500;
        try (CloseableHttpResponse response = post(headers, url, body)) {
            result = response.getStatusLine().getStatusCode();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return result;
    }

    /**
     * 发送阻塞的 Get 请求
     *
     * @param url 请求 Url
     * @return JsonObject 返回结果转换为 JSON
     */
    public JSONObject getJson(String url) {
        return getJson(null, url);
    }

    /**
     * 发送阻塞的 Post 请求
     *
     * @param url 请求 Url
     * @return JsonObject 返回结果转换为 JSON
     */
    public JSONObject postJson(String url, String body) {
        return postJson(null, url, body);
    }

    /**
     * 发送阻塞的 Get 请求
     *
     * @param headers 请求头
     * @param url     请求 Url
     * @return JSONObject 返回结果转换为 JSON
     */
    public JSONObject getJson(Map<String, String> headers, String url) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put("content-type", "application/json; charset=UTF-8");
        try (CloseableHttpResponse response = get(headers, url)) {
            int code = response.getStatusLine().getStatusCode();
            if (code >= 400) {
                logger.warn("response code {} from url:{}", code, url);
            }
            String s = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            EntityUtils.consume(response.getEntity());
            response.close();
            return JSONObject.parseObject(s);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            return new JSONObject();
        }
    }

    /**
     * 发送阻塞的 Post 请求，并且封装内容 zip
     * 考虑到 get 请求不太可能有过多数据，目前只封装 Post 请求
     *
     * @param headers 请求头 nullable
     * @param url     请求 Url
     * @param body    请求体
     */
    public JSONObject postJsonZip(Map<String, String> headers, String url, String body) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put("Content-Encoding", "gzip");
        return postJson(headers, url, body);
    }

    /**
     * 发送阻塞的 Post 请求
     *
     * @param headers 请求头 nullable
     * @param url     请求 Url
     * @param body    请求体
     * @return JSONObject 返回为 Json
     */
    public JSONObject postJson(Map<String, String> headers, String url, String body) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put("content-type", "application/json; charset=UTF-8");
        try (CloseableHttpResponse response = post(headers, url, body)) {
            int code = response.getStatusLine().getStatusCode();
            if (code >= 400) {
                logger.warn("response code {}", code);
            }
            String s = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            EntityUtils.consume(response.getEntity());
            response.close();
            return JSONObject.parseObject(s);
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return new JSONObject();
        }
    }

    private CloseableHttpResponse post(Map<String, String> headers, String url, String body) throws IOException {
        HttpPost post = new HttpPost(url);
        if (headers != null) {
            headers.forEach(post::setHeader);
        }
        post.setConfig(getRequestConfig());
        post.setEntity(new StringEntity(body, StandardCharsets.UTF_8));
        return getClient().execute(post);
    }


    private CloseableHttpResponse get(Map<String, String> headers, String url) throws IOException {
        HttpGet get = new HttpGet(url);
        if (headers != null) {
            headers.forEach(get::setHeader);
        }
        get.setConfig(getRequestConfig());
        return getClient().execute(get);
    }

    public void executeAsync(HttpUriRequest request, Consumer<? super HttpResponse> responseAction, BiConsumer<? super HttpUriRequest, ? super Exception> errorAction) {
        getAsyncClient().execute(request, new FutureCallback<HttpResponse>() {
            @Override
            public void completed(HttpResponse httpResponse) {
                if (responseAction != null) {
                    responseAction.accept(httpResponse);
                }
            }

            @Override
            public void failed(Exception e) {
                if (errorAction != null) {
                    errorAction.accept(request, e);
                }
            }

            @Override
            public void cancelled() {
                logger.warn("Http async request canceled");
            }
        });
    }

    public CloseableHttpAsyncClient getAsyncClient() {
        if (asyncClient != null) {
            if (!asyncClient.isRunning()) {
                asyncClient.start();
            }
            retryAsyncCounter = 0;
            return asyncClient;
        }
        try {
            if (retryAsyncCounter++ >= 10) {
                return null;
            }
            asyncClient = HttpAsyncClientBuilder.create().setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                    .setRedirectStrategy(new DefaultRedirectStrategy()).setDefaultRequestConfig(getRequestConfig())
                    .setSSLContext(getSSLContext()).addInterceptorFirst(getHttpRequestInterceptor())
                    .addInterceptorFirst(getHttpResponseInterceptor()).build();
            return getAsyncClient();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    public CloseableHttpClient getClient() {
        if (client != null) {
            return client;
        }
        try {

            client = HttpClients.custom().setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                    .setRedirectStrategy(new DefaultRedirectStrategy()).setDefaultRequestConfig(getRequestConfig())
                    .setSSLSocketFactory(getSSLFactory()).addInterceptorFirst(getHttpRequestInterceptor())
                    .addInterceptorFirst(getHttpResponseInterceptor()).build();

            return client;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private RequestConfig getRequestConfig() {
        if (requestConfig == null) {
            requestConfig = RequestConfig.custom().setConnectTimeout(5000).setSocketTimeout(5000)
                    .setConnectionRequestTimeout(5000).setCookieSpec(CookieSpecs.STANDARD_STRICT)
                    .setCircularRedirectsAllowed(true).build();
        }
        return requestConfig;
    }

    private SSLConnectionSocketFactory getSSLFactory() throws Exception {
        if (sslFactory != null) {
            return sslFactory;
        }
        sslFactory = new SSLConnectionSocketFactory(getSSLContext()
                , new String[]{"TLSv1"}    // supportedProtocols ,这里可以按需要设置
                , null    // supportedCipherSuites
                , SSLConnectionSocketFactory.getDefaultHostnameVerifier());
        return sslFactory;
    }

    private synchronized SSLContext getSSLContext() throws Exception {
        KeyStore serverKeyStore = KeyStore.getInstance(SERVER_PFX_TYPE);
        KeyStore clientKeyStore = KeyStore.getInstance(CLIENT_PFX_TYPE);

        try (InputStream clientStream = getClass().getClassLoader().getResourceAsStream(CLIENT_PFX_PATH);
             InputStream serverStream = getClass().getClassLoader().getResourceAsStream(SERVER_PFX_PATH)) {
            clientKeyStore.load(clientStream, CLIENT_PFX_PWD.toCharArray());
            serverKeyStore.load(serverStream, SERVER_PFX_PWD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(clientKeyStore, CLIENT_PFX_PWD.toCharArray());
        KeyManager[] keyManagers = kmf.getKeyManagers();

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(serverKeyStore);
        TrustManager[] trustManagers = tmf.getTrustManagers();

        SSLContext sslcontext = SSLContext.getInstance("TLS");
        sslcontext.init(keyManagers, trustManagers, new java.security.SecureRandom());

        return sslcontext;
    }

    /**
     * 解析 Gzip,自带的 类 过滤器
     *
     * @return HttpResponseInterceptor
     */
    private HttpResponseInterceptor getHttpResponseInterceptor() {
        HttpResponseInterceptor httpResponseInterceptor = null;
        try {
            httpResponseInterceptor = (resp, arg1) -> {
                Header[] headers = resp.getHeaders("Content-Encoding");
                for (Header header : headers) {
                    if (header.getValue().equalsIgnoreCase("gzip") || header.getValue().contains("gzip")) {
                        resp.setEntity(new GzipDecompressingEntity(resp.getEntity()));
                        return;
                    }
                }
            };
        } catch (Exception e) {
            logger.error("Error resolving G-zip response", e);
        }
        return httpResponseInterceptor;
    }

    /**
     * 增加全局 接受Gzip,请求头
     *
     * @return HttpRequestInterceptor
     */
    private HttpRequestInterceptor getHttpRequestInterceptor() {
        HttpRequestInterceptor httpRequestInterceptor = null;
        try {
            httpRequestInterceptor = (req, arg1) -> {
                req.addHeader("Accept-Encoding", "gzip, deflate");
                req.addHeader("Connection", "close");
                req.addHeader("Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
            };
        } catch (Exception e) {
            logger.error("Error putting G-zip request header", e);
        }
        return httpRequestInterceptor;
    }



}
