package com.liskovsoft.smartyoutubetv2.common.vot;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VotHttpTest {
    @Test
    public void testNon2xxResponseThrowsVotHttpExceptionBeforeParsing() {
        byte[] truncatedProtobuf = new byte[]{0x0A, 0x72};
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(500)
                        .message("Internal Server Error")
                        .body(ResponseBody.create(MediaType.parse("application/x-protobuf"), truncatedProtobuf))
                        .build())
                .build();
        VotHttp http = new VotHttp(client);

        try {
            http.postProtobuf("/test", new byte[0], Collections.emptyMap());
            fail("Expected VotHttpException for HTTP 500");
        } catch (IOException e) {
            assertTrue("Expected instance of VotHttpException", e instanceof VotHttpException);
            VotHttpException httpEx = (VotHttpException) e;
            assertEquals(500, httpEx.getStatusCode());
        }
    }

    @Test
    public void testServerUnavailableAndRateLimitHelpers() {
        OkHttpClient client503 = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body(ResponseBody.create(MediaType.parse("application/x-protobuf"), new byte[0]))
                        .build())
                .build();
        VotHttp http503 = new VotHttp(client503);

        try {
            http503.postProtobuf("/test", new byte[0], Collections.emptyMap());
            fail("Expected VotHttpException for HTTP 503");
        } catch (IOException e) {
            assertTrue(e instanceof VotHttpException);
            VotHttpException httpEx = (VotHttpException) e;
            assertEquals(503, httpEx.getStatusCode());
            assertTrue(httpEx.isServerUnavailable());
        }

        OkHttpClient client429 = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(ResponseBody.create(MediaType.parse("application/x-protobuf"), new byte[0]))
                        .build())
                .build();
        VotHttp http429 = new VotHttp(client429);

        try {
            http429.postProtobuf("/test", new byte[0], Collections.emptyMap());
            fail("Expected VotHttpException for HTTP 429");
        } catch (IOException e) {
            assertTrue(e instanceof VotHttpException);
            VotHttpException httpEx = (VotHttpException) e;
            assertEquals(429, httpEx.getStatusCode());
            assertTrue(httpEx.isRateLimited());
        }
    }

    @Test
    public void test401ThrowsVotHttpException() {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(401)
                        .message("Unauthorized")
                        .body(ResponseBody.create(MediaType.parse("application/x-protobuf"), new byte[0]))
                        .build())
                .build();
        VotHttp http = new VotHttp(client);

        try {
            http.postProtobuf("/test", new byte[0], Collections.emptyMap());
            fail("Expected VotHttpException for HTTP 401");
        } catch (IOException e) {
            assertTrue("Expected instance of VotHttpException", e instanceof VotHttpException);
            assertEquals(401, ((VotHttpException) e).getStatusCode());
        }
    }
}