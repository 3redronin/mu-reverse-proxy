package io.muserver.samples;

import io.muserver.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Arrays.asList;

public class ReverseProxyHandler implements MuHandler {

    private static final Set<String> HOP_BY_HOP_HEADERS = Collections.unmodifiableSet(new HashSet<>(asList(
        "keep-alive", "transfer-encoding", "te", "connection", "trailer", "upgrade", "proxy-authorization", "proxy-authenticate")));


    private static final Logger log = LoggerFactory.getLogger(ReverseProxyHandler.class);
    private final HttpClient client;
    private final URI target;
    private final AtomicLong counter = new AtomicLong();

    public ReverseProxyHandler(HttpClient client, URI target) {
        this.client = client;
        this.target = target;
    }

    public boolean handle(MuRequest clientReq, final MuResponse clientResp) throws Exception {
        final long start = System.currentTimeMillis();

        URI newTarget = new URI(target.getScheme(), target.getUserInfo(), target.getHost(), target.getPort(), clientReq.uri().getPath(), clientReq.uri().getQuery(), clientReq.uri().getFragment());
        final AsyncHandle asyncHandle = clientReq.handleAsync();
        final long id = counter.incrementAndGet();
        log.info("[" + id + "] Proxying from " + clientReq.uri() + " to " + newTarget);

        Request targetReq = client.newRequest(newTarget);
        targetReq.method(clientReq.method().name());
        boolean hasRequestBody = setHeaders(clientReq, targetReq);

        if (hasRequestBody) {
            DeferredContentProvider targetReqBody = new DeferredContentProvider();
            asyncHandle.setReadListener(new RequestBodyListener() {
                @Override
                public void onDataReceived(ByteBuffer buffer) {
                    targetReqBody.offer(buffer);
                }

                @Override
                public void onComplete() {
                    targetReqBody.close();
                }

                @Override
                public void onError(Throwable t) {
                    targetReqBody.failed(t);
                }
            });
            targetReq.content(targetReqBody);
        }

        targetReq.onResponseHeaders(response -> {
            clientResp.status(response.getStatus());
            HttpFields targetRespHeaders = response.getHeaders();
            for (HttpField targetRespHeader : targetRespHeaders) {
                String lowerName = targetRespHeader.getName().toLowerCase();
                if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
                    continue;
                }
                clientResp.headers().add(targetRespHeader.getName(), asList(targetRespHeader.getValues()));
            }
        });
        targetReq.onResponseContentAsync((response, content, callback) -> asyncHandle.write(content,
            new WriteCallback() {
                @Override
                public void onFailure(Throwable reason) {
                    callback.failed(reason);
                }

                @Override
                public void onSuccess() {
                    callback.succeeded();
                }
            }));
        targetReq.send(result -> {
            try {
                long duration = System.currentTimeMillis() - start;
                if (result.isFailed()) {
                    String errorID = UUID.randomUUID().toString();
                    log.error("Failed to proxy response. ErrorID=" + errorID + " for " + result, result.getFailure());
                    if (result.isFailed() && !clientResp.hasStartedSendingData()) {
                        clientResp.status(502);
                        clientResp.contentType(ContentTypes.TEXT_HTML);
                        clientResp.write("<h1>502 Bad Gateway</h1><p>ErrorID=" + errorID + "</p>");
                    }
                } else {
                    log.info("[" + id + "] completed in " + duration + "ms: " + result);
                }
            } finally {
                asyncHandle.complete();
            }
        });

        return true;
    }

    private boolean setHeaders(MuRequest clientReq, Request targetReq) {
        List<String> customHopByHop = new ArrayList<>();
        String connection = clientReq.headers().get(HttpHeaderNames.CONNECTION);
        if (connection != null) {
            String[] split = connection.split(" *, *");
            for (String s : split) {
                customHopByHop.add(s.toLowerCase());
            }
        }

        boolean hasContentLengthOrTransferEncoding = false;
        for (Map.Entry<String, String> clientHeader : clientReq.headers()) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            if (HOP_BY_HOP_HEADERS.contains(lowKey) || customHopByHop.contains(lowKey)) {
                continue;
            }
            hasContentLengthOrTransferEncoding |= lowKey.equals("content-length") || lowKey.equals("transfer-encoding");
            targetReq.header(key, clientHeader.getValue());
        }
        String proto = clientReq.uri().getScheme();
        String originHost = clientReq.uri().getAuthority();

        targetReq.header("Via", "http://1.1 murp");
        targetReq.header("X-Forwarded-Proto", null);
        targetReq.header("X-Forwarded-Proto", proto);
        targetReq.header("X-Forwarded-Host", null);
        targetReq.header("X-Forwarded-Host", originHost);
        targetReq.header("X-Forwarded-Server", null);

        String murpForwarded = "host=" + originHost + "; proto=" + proto;
        String curFowarded = clientReq.headers().get("Forwarded", null);
        if (curFowarded != null) {
            murpForwarded = curFowarded + ", " + murpForwarded;
        }
        targetReq.header("Forwarded", null);
        targetReq.header("Forwarded", murpForwarded);

        return hasContentLengthOrTransferEncoding;
    }
}
