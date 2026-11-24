/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.servlets;

import java.io.File;
import java.io.FileWriter;
import java.net.SocketTimeoutException;

import jakarta.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.compat.JrePlatform;

/*
 * Tests the handling of the request body by the CGI Servlet. The CGI script
 * used by these tests copies its standard input to its standard output so the
 * script only exits - and the request only completes - once the CGI Servlet
 * has closed the standard input of the script. If the standard input is never
 * closed, the request processing thread and the CGI process are held
 * indefinitely.
 */
public class TestCGIServletRequestBody extends TomcatBaseTest {

    private static final String CRLF = SimpleHttpClient.CRLF;

    private static final String PAYLOAD = "Hello-CGI-request-body";

    private static final String OUTPUT_START = "CGI-OUTPUT-START";
    private static final String OUTPUT_END = "CGI-OUTPUT-END";

    private static final int SO_TIMEOUT = 15000;


    @Test
    public void testPostWithContentLength() throws Exception {
        doTest("POST", "Content-Length: " + PAYLOAD.length() + CRLF, PAYLOAD, PAYLOAD);
    }


    @Test
    public void testPostChunked() throws Exception {
        // No content length so CONTENT_LENGTH is passed to the script as ""
        String body = Integer.toHexString(PAYLOAD.length()) + CRLF + PAYLOAD + CRLF + "0" + CRLF + CRLF;
        doTest("POST", "Transfer-Encoding: chunked" + CRLF, body, PAYLOAD);
    }


    @Test
    public void testPostChunkedEmptyBody() throws Exception {
        doTest("POST", "Transfer-Encoding: chunked" + CRLF, "0" + CRLF + CRLF, null);
    }


    @Test
    public void testPostEmptyBody() throws Exception {
        doTest("POST", "Content-Length: 0" + CRLF, "", null);
    }


    @Test
    public void testGet() throws Exception {
        doTest("GET", "", "", null);
    }


    private void doTest(String method, String extraHeaders, String body, String expectedPayload)
            throws Exception {

        Assume.assumeFalse("This test uses a POSIX shell CGI script", JrePlatform.IS_WINDOWS);

        Tomcat tomcat = getTomcatInstance();

        File appDir = new File(getTemporaryDirectory(), "cgitest");
        Assert.assertTrue(appDir.mkdirs());
        addDeleteOnTearDown(appDir);

        File cgiDir = new File(appDir, "WEB-INF/cgi");
        Assert.assertTrue(cgiDir.mkdirs());

        File script = new File(cgiDir, "echo.sh");
        try (FileWriter fw = new FileWriter(script)) {
            fw.write("#!/bin/sh\n");
            fw.write("echo \"Content-Type: text/plain\"\n");
            fw.write("echo\n");
            fw.write("echo \"" + OUTPUT_START + "\"\n");
            // Blocks until standard input is closed
            fw.write("cat\n");
            fw.write("echo\n");
            fw.write("echo \"" + OUTPUT_END + "\"\n");
        }
        Assert.assertTrue(script.setExecutable(true));

        Context ctx = tomcat.addContext("", appDir.getAbsolutePath());
        ctx.setPrivileged(true);

        Wrapper cgi = Tomcat.addServlet(ctx, "cgi", new CGIServlet());
        cgi.addInitParameter("cgiPathPrefix", "WEB-INF/cgi");
        cgi.addInitParameter("executable", "");
        ctx.addServletMapping("/cgi/*", "cgi");

        tomcat.start();

        Client client = new Client();
        client.setPort(getPort());
        // @formatter:off
        client.setRequest(new String[] {
                method + " /cgi/echo.sh HTTP/1.1" + CRLF +
                    "Host: localhost:" + getPort() + CRLF +
                    extraHeaders +
                    "Connection: close" + CRLF +
                    CRLF +
                    body
                });
        // @formatter:on
        client.connect(10000, SO_TIMEOUT);
        try {
            client.processRequest(true);
        } catch (SocketTimeoutException ste) {
            Assert.fail("No response from the CGI Servlet - the standard input of the CGI script was not closed");
        } finally {
            client.disconnect();
        }

        Assert.assertEquals(HttpServletResponse.SC_OK, client.getStatusCode());
        String responseBody = client.getResponseBody();
        Assert.assertTrue(responseBody, responseBody.contains(OUTPUT_START));
        Assert.assertTrue(responseBody, responseBody.contains(OUTPUT_END));
        if (expectedPayload != null) {
            Assert.assertTrue(responseBody, responseBody.contains(expectedPayload));
        }
    }


    private static final class Client extends SimpleHttpClient {

        @Override
        public boolean isResponseBodyOK() {
            return true;
        }
    }
}
