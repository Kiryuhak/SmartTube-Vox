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
import static org.junit.Assert.fail;

public class VotHttpTest {
    @Test
    public void testNon2xxResponseThrowsIOExceptionBeforeParsing() {
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
            fail("Expected IOException for HTTP 500");
        } catch (IOException e) {
            assertEquals("VOT HTTP error: 500", e.getMessage());
        }
    }
}
