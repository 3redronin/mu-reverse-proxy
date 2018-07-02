package io.muserver.samples;


import io.muserver.*;
import io.muserver.handlers.ResourceHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.client.util.MultiPartContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class ReverseProxyHandlerTest {

    private static HttpClient client;
    private static HttpClient proxyClient;

    @BeforeClass
    public static void startClient() throws Exception {
        client = new HttpClient(new SslContextFactory(true));
        client.start();
        proxyClient = App.clientSuitableForProxyingRequests();
    }

    @AfterClass
    public static void stopClient() throws Exception {
        client.stop();
        proxyClient.stop();
    }

    private File staticDir = new File("sample-static");
    private File guangzhouJpeg = new File(staticDir, "images/guangzhou, china.jpeg");

    private MuServer reverseProxy, target;

    @Test
    public void itProxiesAllRequestHeadersExceptForHopByHopOnes() throws Exception {
        Map<String, String> requestHeaders = new HashMap<>();

        target = httpsServer()
            .addHandler((request, response) -> {
                request.headers().forEach(h -> requestHeaders.put(h.getKey(), h.getValue()));
                return true;
            })
            .start();

        reverseProxy = httpServer()
            .addHandler(new ReverseProxyHandler(proxyClient, target.uri()))
            .start();
        String reverseProxyHostAndPort = reverseProxy.uri().getAuthority();

        client.newRequest(reverseProxy.uri())
            .agent("Dan, Agent")
            .header("Connection", "Foo")
            .header("foo", "I'm a custom hop-by-hop header")
            .send();
        assertThat(requestHeaders.entrySet(), hasSize(7));
        assertThat(requestHeaders.get("User-Agent"), is("Dan, Agent"));
        assertThat(requestHeaders.get("Host"), is(reverseProxyHostAndPort));
        assertThat(requestHeaders.get("Via"), is("HTTP/1.1 murp"));
        assertThat(requestHeaders.get("X-Forwarded-Proto"), is("http"));
        assertThat(requestHeaders.get("X-Forwarded-Host"), is(reverseProxyHostAndPort));
        assertThat(requestHeaders.get("Forwarded"), is("by=" + ReverseProxyHandler.ipAddress + "; for=127.0.0.1; host=" + reverseProxyHostAndPort + "; proto=http"));
        assertThat(requestHeaders.get("Accept-Encoding"), is("gzip"));
    }


    @Test
    public void responseHeadersAreProxiedBack() throws Exception {
        target = httpsServer()
            .addHandler((request, response) -> {
                response.status(299);
                response.headers()
                    .add("Date", "Mon, 02 Jul 2018 13:30:53 GMT")
                    .add("X-Duplicate", "Some, Value")
                    .add("Connection", "Bar")
                    .add("bar", "This is a custom hop-by-hop header")
                    .add("X-Duplicate", "And more!");
                return true;
            })
            .start();

        reverseProxy = httpServer()
            .addHandler(new ReverseProxyHandler(proxyClient, target.uri()))
            .start();

        ContentResponse resp = client
            .newRequest(reverseProxy.uri())
            .send();
        assertThat(resp.getStatus(), is(299));
        assertThat(resp.getHeaders().toString(), resp.getHeaders().size(), is(4));
        assertThat(resp.getHeaders().getValuesList("Content-Length"), contains("0"));
        assertThat(resp.getHeaders().getValuesList("Date"), contains("Mon, 02 Jul 2018 13:30:53 GMT"));
        assertThat(resp.getHeaders().getValuesList("X-Duplicate"), contains("Some, Value", "And more!"));
    }

    @Test
    public void itCanProxyFiles() throws Exception {
        target = httpsServer()
            .addHandler(ResourceHandler.fileHandler(staticDir))
            .start();

        reverseProxy = httpServer()
            .addHandler(new ReverseProxyHandler(proxyClient, target.uri()))
            .start();

        ContentResponse resp = client.GET(reverseProxy.uri().resolve("/images/guangzhou%2C+china.jpeg"));
        assertThat(resp.getMediaType(), is("image/jpeg"));
        assertThat(resp.getContent(), equalTo(guangzhouImageAsBytes()));
    }

    private byte[] guangzhouImageAsBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (FileInputStream fis = new FileInputStream(guangzhouJpeg)) {
            Mutils.copy(fis, baos, 8192);
        }
        return baos.toByteArray();
    }


    @Test
    public void filesCanBeUploaded() throws Exception {

        Object[] actual = new Object[2];

        target = httpsServer()
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                UploadedFile uploadedFile = request.uploadedFile("theFile");
                actual[0] = uploadedFile.filename();
                actual[1] = uploadedFile.asBytes();
            })
            .start();

        reverseProxy = httpServer()
            .addHandler(new ReverseProxyHandler(proxyClient, target.uri()))
            .start();

        MultiPartContentProvider requestBody = new MultiPartContentProvider();
        requestBody.addFilePart("theFile", "guangzhou china.jpeg", new BytesContentProvider(guangzhouImageAsBytes()), null);
        requestBody.close();

        client.newRequest(reverseProxy.uri().resolve("/upload"))
            .method(HttpMethod.POST)
            .content(requestBody)
            .send();

        assertThat(actual[0], equalTo("guangzhou china.jpeg"));
        assertThat(actual[1], equalTo(guangzhouImageAsBytes()));
    }

    @Before
    public void check() {
        if (!staticDir.isDirectory()) {
            throw new RuntimeException("Expected directory at " + Mutils.fullPath(staticDir));
        }
    }

    @After
    public void stop() {
        for (MuServer mus : asList(reverseProxy, target)) {
            if (mus != null) mus.stop();
        }
    }


}