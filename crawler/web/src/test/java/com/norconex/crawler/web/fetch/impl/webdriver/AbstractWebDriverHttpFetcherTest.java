/* Copyright 2018-2023 Norconex Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.norconex.crawler.web.fetch.impl.webdriver;

import static com.norconex.crawler.web.WebsiteMock.serverUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.MediaType.HTML_UTF_8;

import java.awt.Dimension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;

import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerSettings;
import org.openqa.selenium.Capabilities;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.BrowserWebDriverContainer.VncRecordingMode;
import org.testcontainers.containers.output.Slf4jLogConsumer;

import com.norconex.commons.lang.img.MutableImage;
import com.norconex.commons.lang.xml.XML;
import com.norconex.crawler.core.doc.CrawlDocState;
import com.norconex.crawler.core.fetch.FetchException;
import com.norconex.crawler.web.TestWebCrawlSession;
import com.norconex.crawler.web.WebStubber;
import com.norconex.crawler.web.WebTestUtil;
import com.norconex.crawler.web.WebsiteMock;
import com.norconex.crawler.web.fetch.HttpFetchRequest;
import com.norconex.crawler.web.fetch.HttpMethod;
import com.norconex.crawler.web.fetch.util.DocImageHandler.Target;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@MockServerSettings
public abstract class AbstractWebDriverHttpFetcherTest  {

    private static final int LARGE_CONTENT_MIN_SIZE = 5 * 1024 *1024;

    @BeforeEach
    void beforeEach(ClientAndServer client) {
        Testcontainers.exposeHostPorts(client.getPort());
    }

    @Test
    void testFetchingJsGeneratedContent(ClientAndServer client) {
        WebsiteMock.whenJsRenderedWebsite(client);

        var mem = TestWebCrawlSession
            .forStartUrls(hostUrl(client, "/index.html"))
            .crawlerSetup(cfg -> {
                cfg.setHttpFetchers(createWebDriverHttpFetcher());
                cfg.setMaxDepth(0);
            })
            .crawl();

        assertThat(mem.getRequestCount()).isOne();
        assertThat(WebTestUtil.docText(mem.getUpsertRequests().get(0)))
                .contains("JavaScript-rendered!");
    }

    @Test
    void testTakeScreenshots(ClientAndServer client) throws IOException {

        WebsiteMock.whenJsRenderedWebsite(client);

        var mem = TestWebCrawlSession
            .forStartUrls(hostUrl(client, "/apple.html"))
            .crawlerSetup(cfg -> {
                var h = new ScreenshotHandler();
                h.setTargets(List.of(Target.METADATA));
                h.setTargetMetaField("myimage");
                h.setCssSelector("#applePicture");

                var f = createWebDriverHttpFetcher();
                f.setScreenshotHandler(h);
                cfg.setHttpFetchers(f);
                cfg.setMaxDepth(0);
            })
            .crawl();

        assertThat(mem.getUpsertCount()).isOne();

        var img = MutableImage.fromBase64String(
                mem.getUpsertRequests().get(0).getMetadata().getString(
                        "myimage"));
        assertThat(img.getWidth()).isEqualTo(350);
        assertThat(img.getHeight()).isEqualTo(350);
    }

    // Test using sniffer for capturing HTTP response headers and
    // using sniffer with large content (test case for:
    // https://github.com/Norconex/collector-http/issues/751)
    @Test
    void testHttpSniffer(ClientAndServer client) {

        var path = "/sniffHeaders.html";

        client
            .when(request(path))
            .respond(response()
                .withHeader("multiKey", "multiVal1", "multiVal2")
                .withHeader("singleKey", "singleValue")
                .withBody(WebsiteMock
                    .htmlPage()
                    .body(RandomStringUtils.randomAlphanumeric(
                            LARGE_CONTENT_MIN_SIZE))
                    .build()));

        var mem = TestWebCrawlSession
            // using serverUrl (localhost) here since it will be invoked
            // by proxy, which resides locally.
            .forStartUrls(serverUrl(client, path))
            .crawlerSetup(cfg -> {
                var snifCfg = new HttpSnifferConfig();
                snifCfg.setHost("host.testcontainers.internal");
                snifCfg.setPort(freePort());
                // also test sniffer with large content
                snifCfg.setMaxBufferSize(6 * 1024 * 1024);
                LOG.debug("Random HTTP Sniffer proxy port: {}",
                        snifCfg.getPort());
                Testcontainers.exposeHostPorts(
                        client.getPort(), snifCfg.getPort());
                var f = createWebDriverHttpFetcher();
                f.getConfig().setHttpSnifferConfig(snifCfg);
                cfg.setHttpFetchers(f);
                cfg.setMaxDepth(0);
            })
            .crawl();

        assertThat(mem.getUpsertRequests()).hasSize(1);
        var meta = mem.getUpsertRequests().get(0).getMetadata();
        assertThat(meta.getStrings("multiKey")).containsExactly(
                "multiVal1", "multiVal2");
        assertThat(meta.getString("singleKey")).isEqualTo("singleValue");

        assertThat(WebTestUtil.docText(mem.getUpsertRequests().get(0)).length())
            .isGreaterThanOrEqualTo(LARGE_CONTENT_MIN_SIZE);
    }

    @Test
    void testPageScript(ClientAndServer client) {
        var path = "/pageScript.html";

        client
            .when(request(path))
            .respond(response()
                .withBody(
                    WebsiteMock
                        .htmlPage()
                        .body("""
                            <h1>Page Script Test</h1>
                            <p>H1 above should be replaced.</p>
                            """)
                        .build(),
                    HTML_UTF_8));

        var mem = TestWebCrawlSession
            .forStartUrls(hostUrl(client, path))
            .crawlerSetup(cfg -> {
                var f = createWebDriverHttpFetcher();
                f.getConfig().setEarlyPageScript("document.title='Awesome!';");
                f.getConfig().setLatePageScript("""
                    document.getElementsByTagName('h1')[0].innerHTML='Melon';
                    """);
                cfg.setHttpFetchers(f);
                cfg.setMaxDepth(0);
            })
            .crawl();

        var doc = mem.getUpsertRequests().get(0);
        assertThat(doc.getMetadata().getString("title")).isEqualTo("Awesome!");
        assertThat(WebTestUtil.docText(doc)).contains("Melon");
    }

    @Test
    void testResolvingUserAgent(ClientAndServer client) {
        var path = "/userAgent.html";

        client
            .when(request(path))
            .respond(response()
                .withBody(
                    WebsiteMock
                        .htmlPage()
                        .body("<p>Should grab user agent from browser.</p>")
                        .build(),
                    HTML_UTF_8));

        var fetcher = createWebDriverHttpFetcher();
        TestWebCrawlSession
                .forStartUrls(hostUrl(client, path))
                .crawlerSetup(cfg -> {
                    cfg.setHttpFetchers(fetcher);
                    cfg.setMaxDepth(0);
                    // test setting a bunch of other params
                    var fetchCfg = fetcher.getConfig();
                    fetchCfg.setWindowSize(new Dimension(640, 480));
                    fetchCfg.setPageLoadTimeout(10_1000);
                    fetchCfg.setImplicitlyWait(1000);
                    fetchCfg.setScriptTimeout(10_000);
                    fetchCfg.setWaitForElementSelector("p");
                    fetchCfg.setWaitForElementTimeout(10_000);
                })
                .crawl();

        assertThat(fetcher.getUserAgent()).isNotBlank();
        assertThat(fetcher.getUserAgent()).isNotBlank();
    }

    @Test
    void testUnsupportedHttpMethod() throws FetchException {
        var response = new WebDriverHttpFetcher().fetch(
                new HttpFetchRequest(
                        WebStubber.crawlDocHtml("http://example.com"),
                        HttpMethod.HEAD));
        assertThat(response.getReasonPhrase()).contains("To obtain headers");
        assertThat(response.getCrawlDocState()).isEqualTo(
                CrawlDocState.UNSUPPORTED);
    }

    @Test
    void testWriteRead() {
        assertThatNoException().isThrownBy(() -> {
            XML.assertWriteRead(new WebDriverHttpFetcher(), "fetcher");
        });
    }

    //--- Private/Protected ----------------------------------------------------

    private WebDriverHttpFetcher createWebDriverHttpFetcher() {
        var wdCfg = new WebDriverHttpFetcherConfig();
        wdCfg.setRemoteURL(getBrowserDriver().getSeleniumAddress());
        wdCfg.setBrowser(getBrowserType());
        return new WebDriverHttpFetcher(wdCfg);
    }
    private String hostUrl(ClientAndServer client, String path) {
        return "http://host.testcontainers.internal:%s%s".formatted(
                client.getLocalPort(), path);
    }

    private int freePort() {
        try (var serverSocket = new ServerSocket(0)) {
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected abstract BrowserWebDriverContainer<?> getBrowserDriver();
    protected abstract Browser getBrowserType();

    @SuppressWarnings("resource")
    protected static BrowserWebDriverContainer<?> createWebDriverContainer(
            Capabilities capabilities) {
        return new BrowserWebDriverContainer<>()
            .withCapabilities(capabilities)
            .withAccessToHost(true)
            .withRecordingMode(VncRecordingMode.SKIP, null)
            .withLogConsumer(new Slf4jLogConsumer(LOG));
    }
}
