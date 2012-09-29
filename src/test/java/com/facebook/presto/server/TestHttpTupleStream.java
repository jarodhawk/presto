/*
 * Copyright 2004-present Facebook. All Rights Reserved.
 */
package com.facebook.presto.server;

import com.facebook.presto.TupleInfo;
import com.facebook.presto.block.Cursor;
import com.facebook.presto.server.QueryDriversTupleStream.QueryDriversBlockIterator;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Binder;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Scopes;
import io.airlift.configuration.ConfigurationFactory;
import io.airlift.configuration.ConfigurationModule;
import io.airlift.event.client.InMemoryEventModule;
import io.airlift.http.client.ApacheHttpClient;
import io.airlift.http.client.AsyncHttpClient;
import io.airlift.http.client.HttpClient;
import io.airlift.http.server.testing.TestingHttpServer;
import io.airlift.http.server.testing.TestingHttpServerModule;
import io.airlift.jaxrs.JaxrsModule;
import io.airlift.json.JsonModule;
import io.airlift.node.testing.TestingNodeModule;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class TestHttpTupleStream
{
    private HttpClient client;
    private TestingHttpServer server1;
    private TestingHttpServer server2;
    private TestingHttpServer server3;

    @BeforeMethod
    public void setup()
            throws Exception
    {
        try {
            server1 = createServer();
            server2 = createServer();
            server3 = createServer();
            client = new ApacheHttpClient();
        }
        catch (Exception | Error e) {
            teardown();
        }
    }

    private TestingHttpServer createServer()
            throws Exception
    {
        Injector injector = Guice.createInjector(
                new TestingNodeModule(),
                new InMemoryEventModule(),
                new TestingHttpServerModule(),
                new JsonModule(),
                new JaxrsModule(),
                new Module()
                {
                    @Override
                    public void configure(Binder binder)
                    {
                        binder.bind(QueryResource.class).in(Scopes.SINGLETON);
                        binder.bind(QueryManager.class).to(SimpleQueryManager.class).in(Scopes.SINGLETON);
                        binder.bind(UncompressedBlockMapper.class).in(Scopes.SINGLETON);
                        binder.bind(UncompressedBlocksMapper.class).in(Scopes.SINGLETON);
                    }
                },
                new ConfigurationModule(new ConfigurationFactory(ImmutableMap.<String, String>of())));

        TestingHttpServer server = injector.getInstance(TestingHttpServer.class);
        server.start();
        return server;
    }

    @AfterMethod
    public void teardown()
            throws Exception
    {
        Thread.sleep(1000);
        if (server1 != null) {
            server1.stop();
        }
        if (server2 != null) {
            server2.stop();
        }
        if (server3 != null) {
            server3.stop();
        }
    }

    @Test
    public void testQuery()
            throws Exception
    {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            AsyncHttpClient httpClient = new AsyncHttpClient(client, executor);
            QueryDriversTupleStream tupleStream = new QueryDriversTupleStream(TupleInfo.SINGLE_VARBINARY, 10,
                    new HttpQueryProvider(httpClient, server1.getBaseUrl().resolve("/v1/presto/query")),
                    new HttpQueryProvider(httpClient, server2.getBaseUrl().resolve("/v1/presto/query")),
                    new HttpQueryProvider(httpClient, server3.getBaseUrl().resolve("/v1/presto/query"))
            );

            int count = 0;
            Cursor cursor = tupleStream.cursor();
            while (cursor.advanceNextPosition()) {
                count++;
            }
            assertEquals(count, 312 * 3);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testCancel()
            throws Exception
    {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            AsyncHttpClient httpClient = new AsyncHttpClient(client, executor);
            QueryDriversTupleStream tupleStream = new QueryDriversTupleStream(TupleInfo.SINGLE_VARBINARY, 10,
                    new HttpQueryProvider(httpClient, server1.getBaseUrl().resolve("/v1/presto/query")),
                    new HttpQueryProvider(httpClient, server2.getBaseUrl().resolve("/v1/presto/query")),
                    new HttpQueryProvider(httpClient, server3.getBaseUrl().resolve("/v1/presto/query"))
            );

            int count = 0;
            QueryDriversBlockIterator iterator = tupleStream.iterator();
            while (count < 20 && iterator.hasNext()) {
                iterator.next();
                count++;
            }
            assertEquals(count, 20);

            // verify we have more data
            assertTrue(iterator.hasNext());

            // cancel the iterator
            iterator.cancel();

            // due to buffering in the iterator, we still have one more element, so read it and
            // verify there are no more elements
            assertTrue(iterator.hasNext());
            assertNotNull(iterator.next());
            assertFalse(iterator.hasNext());
        }
        finally {
            executor.shutdownNow();
        }
    }
}