/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.coyote.http2;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.Request;
import org.apache.coyote.http11.AbstractHttp11Protocol;

/**
 * Tests that the request and response associated with a stream that ended with an asynchronous error or an
 * asynchronous timeout are not returned to the pool for re-use. A non-container thread may still be using the request
 * and/or response in that case and re-using them could result in data written by the application for one stream being
 * written to a different stream.
 */
public class TestStreamRequestResponseReuse extends Http2TestBase {

    private final CountDownLatch asyncErrorCompleteLatch = new CountDownLatch(1);
    private final CountDownLatch writerThreadEndLatch = new CountDownLatch(1);


    @Test
    public void testAsyncErrorRequestResponseNotReused() throws Exception {
        http2Connect();

        TrackingHttp2Protocol trackingProtocol = (TrackingHttp2Protocol) http2Protocol;

        // Reset connection window size after initial response
        sendWindowUpdate(0, SimpleServlet.CONTENT_LENGTH);

        // Stream 3 - asynchronous request with the response written by a non-container thread
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);
        buildGetRequest(frameHeader, headersPayload, null, 3, "/asyncError");
        writeFrame(frameHeader, headersPayload);

        // Headers
        parser.readFrame();
        // Body
        parser.readFrame();

        // Reset the stream while the non-container thread is still writing to trigger an asynchronous error
        sendRst(3, Http2Error.CANCEL.getCode());

        // Wait for the container to complete the asynchronous request after the error
        Assert.assertTrue(asyncErrorCompleteLatch.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(writerThreadEndLatch.await(10, TimeUnit.SECONDS));

        doTestNotReused(trackingProtocol);
    }


    @Test
    public void testAsyncTimeoutRequestResponseNotReused() throws Exception {
        http2Connect();

        TrackingHttp2Protocol trackingProtocol = (TrackingHttp2Protocol) http2Protocol;

        // Reset connection window size after initial response
        sendWindowUpdate(0, SimpleServlet.CONTENT_LENGTH);

        // Stream 3 - asynchronous request that is never completed by the application
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);
        buildGetRequest(frameHeader, headersPayload, null, 3, "/asyncTimeout");
        writeFrame(frameHeader, headersPayload);

        // Wait for the asynchronous timeout to fire and the container to complete the response
        int frames = 0;
        while (!output.getTrace().contains("3-EndOfStream") && frames < 20) {
            parser.readFrame();
            frames++;
        }
        String trace = output.getTrace();
        Assert.assertTrue(trace, trace.contains("3-EndOfStream"));
        Assert.assertTrue(trace, trace.contains("3-Header-[:status]-[500]"));

        doTestNotReused(trackingProtocol);
    }


    private void doTestNotReused(TrackingHttp2Protocol trackingProtocol) throws Exception {
        output.clearTrace();

        // Stream 5 - a simple request that completes cleanly
        byte[] frameHeader = new byte[9];
        ByteBuffer headersPayload = ByteBuffer.allocate(128);
        buildGetRequest(frameHeader, headersPayload, null, 5, "/simple");
        writeFrame(frameHeader, headersPayload);

        int frames = 0;
        while (!output.getTrace().contains("5-EndOfStream") && frames < 50) {
            parser.readFrame();
            frames++;
        }
        String trace = output.getTrace();
        Assert.assertTrue(trace, trace.contains("5-EndOfStream"));
        Assert.assertTrue(trace, trace.contains("5-Header-[:status]-[200]"));

        // Stream 3 was the first stream to obtain a request and response from the pool, stream 5 the second
        Request stream3Request = trackingProtocol.getPopped(0);
        Request stream5Request = trackingProtocol.getPopped(1);
        Assert.assertNotNull(stream3Request);
        Assert.assertNotNull(stream5Request);

        // Stream 5 ended cleanly so its request and response are returned to the pool
        Assert.assertTrue(trackingProtocol.awaitPushed(stream5Request, 10, TimeUnit.SECONDS));

        /*
         * The request and response of the stream that ended with an error / timeout must never be re-used - neither by
         * stream 5 nor by being returned to the pool once stream 3 obtained them.
         */
        Assert.assertNotSame(stream3Request, stream5Request);
        Assert.assertFalse(trackingProtocol.isPushed(stream3Request));
    }


    @Override
    protected void enableHttp2(long maxConcurrentStreams, boolean tls, long readTimeout, long writeTimeout,
            long keepAliveTimeout, long streamReadTimout, long streamWriteTimeout) {
        Tomcat tomcat = getTomcatInstance();
        Connector connector = tomcat.getConnector();
        Assert.assertTrue(connector.setProperty("useAsyncIO", Boolean.toString(useAsyncIO)));
        http2Protocol = new TrackingHttp2Protocol();
        http2Protocol.setReadTimeout(readTimeout);
        http2Protocol.setWriteTimeout(writeTimeout);
        http2Protocol.setKeepAliveTimeout(keepAliveTimeout);
        http2Protocol.setStreamReadTimeout(streamReadTimout);
        http2Protocol.setStreamWriteTimeout(streamWriteTimeout);
        http2Protocol.setMaxConcurrentStreams(maxConcurrentStreams);
        http2Protocol.setHttp11Protocol((AbstractHttp11Protocol<?>) connector.getProtocolHandler());
        connector.addUpgradeProtocol(http2Protocol);
    }


    @Override
    protected void configureAndStartWebApplication() throws LifecycleException {
        Tomcat tomcat = getTomcatInstance();

        Context ctxt = getProgrammaticRootContext();
        // This is the target of the HTTP/2 upgrade request
        Tomcat.addServlet(ctxt, "simple", new SimpleServlet());
        ctxt.addServletMappingDecoded("/simple", "simple");
        Wrapper w = Tomcat.addServlet(ctxt, "asyncError",
                new AsyncErrorServlet(asyncErrorCompleteLatch, writerThreadEndLatch));
        w.setAsyncSupported(true);
        ctxt.addServletMappingDecoded("/asyncError", "asyncError");
        w = Tomcat.addServlet(ctxt, "asyncTimeout", new AsyncTimeoutServlet());
        w.setAsyncSupported(true);
        ctxt.addServletMappingDecoded("/asyncTimeout", "asyncTimeout");

        tomcat.start();
    }


    private static class TrackingHttp2Protocol extends Http2Protocol {

        private final List<Request> popped = new ArrayList<>();
        private final List<Request> pushed = new ArrayList<>();

        @Override
        public String getHttpUpgradeName(boolean isSSLEnabled) {
            return "h2c";
        }

        @Override
        Request popRequestAndResponse() {
            Request requestAndResponse = super.popRequestAndResponse();
            synchronized (popped) {
                popped.add(requestAndResponse);
            }
            /*
             * The request and response may have been added to the pool by a previous stream (e.g. the HTTP/1.1 upgrade
             * request used by stream 1). Only track pushes that happen after the request and response were obtained by
             * the current stream.
             */
            synchronized (pushed) {
                Iterator<Request> iter = pushed.iterator();
                while (iter.hasNext()) {
                    if (iter.next() == requestAndResponse) {
                        iter.remove();
                    }
                }
            }
            return requestAndResponse;
        }

        @Override
        void pushRequestAndResponse(Request requestAndResponse) {
            synchronized (pushed) {
                pushed.add(requestAndResponse);
                pushed.notifyAll();
            }
            super.pushRequestAndResponse(requestAndResponse);
        }

        Request getPopped(int index) {
            synchronized (popped) {
                if (index < popped.size()) {
                    return popped.get(index);
                }
                return null;
            }
        }

        boolean isPushed(Request requestAndResponse) {
            synchronized (pushed) {
                for (Request request : pushed) {
                    if (request == requestAndResponse) {
                        return true;
                    }
                }
                return false;
            }
        }

        boolean awaitPushed(Request requestAndResponse, long timeout, TimeUnit unit) throws InterruptedException {
            long end = System.nanoTime() + unit.toNanos(timeout);
            synchronized (pushed) {
                while (!isPushed(requestAndResponse)) {
                    long remaining = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime());
                    if (remaining <= 0) {
                        return false;
                    }
                    pushed.wait(remaining);
                }
                return true;
            }
        }
    }


    private static class AsyncErrorServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private final transient CountDownLatch completeLatch;
        private final transient CountDownLatch writerThreadEndLatch;

        AsyncErrorServlet(CountDownLatch completeLatch, CountDownLatch writerThreadEndLatch) {
            this.completeLatch = completeLatch;
            this.writerThreadEndLatch = writerThreadEndLatch;
        }

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            AsyncContext asyncContext = req.startAsync();
            asyncContext.addListener(new CompleteOnErrorListener(completeLatch));

            Thread writer = new Thread(new MessageGenerator(resp, writerThreadEndLatch));
            writer.start();
        }
    }


    private static final class MessageGenerator implements Runnable {

        private final HttpServletResponse resp;
        private final CountDownLatch writerThreadEndLatch;

        MessageGenerator(HttpServletResponse resp, CountDownLatch writerThreadEndLatch) {
            this.resp = resp;
            this.writerThreadEndLatch = writerThreadEndLatch;
        }

        @Override
        public void run() {
            try {
                resp.setContentType("text/plain");
                resp.setCharacterEncoding("UTF-8");
                PrintWriter pw = resp.getWriter();

                while (true) {
                    pw.println("OK");
                    pw.flush();
                    if (pw.checkError()) {
                        throw new IOException();
                    }
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                // Expect async error handler to handle clean-up
            } finally {
                writerThreadEndLatch.countDown();
            }
        }
    }


    private static final class CompleteOnErrorListener implements AsyncListener {

        private final CountDownLatch completeLatch;

        CompleteOnErrorListener(CountDownLatch completeLatch) {
            this.completeLatch = completeLatch;
        }

        @Override
        public void onComplete(AsyncEvent event) throws IOException {
            completeLatch.countDown();
        }

        @Override
        public void onTimeout(AsyncEvent event) throws IOException {
            // NO-OP
        }

        @Override
        public void onError(AsyncEvent event) throws IOException {
            event.getAsyncContext().complete();
        }

        @Override
        public void onStartAsync(AsyncEvent event) throws IOException {
            // NO-OP
        }
    }


    private static class AsyncTimeoutServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            // Neither the application nor a listener completes the request so the container has to handle the timeout
            AsyncContext asyncContext = req.startAsync();
            asyncContext.setTimeout(1000);
        }
    }
}
