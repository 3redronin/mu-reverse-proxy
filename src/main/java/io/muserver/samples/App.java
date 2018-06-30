package io.muserver.samples;

import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.UploadedFile;
import io.muserver.handlers.ResourceHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.util.HttpCookieStore;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.MuServerBuilder.muServer;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {

        File staticDir = new File("sample-static");

        // start a target service
        MuServer target = httpsServer()
            .addHandler((request, response) -> {
                log.info("Target app received " + request + " >> " + request.headers());
                return false;
            })
            .addHandler(ResourceHandler.fileHandler(staticDir))
            .addHandler(Method.POST, "/upload", (request, response, pathParams) -> {
                UploadedFile uploadedFile = request.uploadedFile("theFile");
                String filename = uploadedFile.filename();
                File dest = new File(new File(staticDir, "uploads"), filename);
                uploadedFile.saveTo(dest);
                response.contentType(ContentTypes.TEXT_HTML);
                response.write("Download: <a href=\"/uploads/" + filename + "\">" + filename + "</a>");
            })
            .addShutdownHook(true)
            .start();
        log.info("Started target server at " + target.uri());


        MuServer reverseProxy = muServer()
            .withHttpPort(20000)
            .addShutdownHook(true)
            .addHandler(new ReverseProxyHandler(createClient(), target.uri()))
            .start();

        log.info("Reverse proxy started at " + reverseProxy.uri());
    }

    private static HttpClient createClient() throws Exception {

        int selectors = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(selectors), new SslContextFactory(true));
        client.setFollowRedirects(false);
        client.setCookieStore(new HttpCookieStore.Empty());
        client.setMaxConnectionsPerDestination(256);
        client.setAddressResolutionTimeout(15000);
        client.setConnectTimeout(15000);
        client.setIdleTimeout(30000);
        client.setUserAgentField(null);
        client.start();

        client.getContentDecoderFactories().clear();

        return client;
    }

}
