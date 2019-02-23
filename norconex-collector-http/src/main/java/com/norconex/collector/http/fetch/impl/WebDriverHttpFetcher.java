/* Copyright 2018-2019 Norconex Inc.
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
package com.norconex.collector.http.fetch.impl;

import java.awt.Dimension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map.Entry;
import java.util.logging.Level;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.LoggingPreferences;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.service.DriverService;
import org.openqa.selenium.remote.service.DriverService.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.norconex.collector.core.CollectorException;
import com.norconex.collector.core.crawler.Crawler;
import com.norconex.collector.core.crawler.CrawlerEvent;
import com.norconex.collector.core.data.CrawlState;
import com.norconex.collector.http.data.HttpCrawlState;
import com.norconex.collector.http.doc.HttpDocument;
import com.norconex.collector.http.doc.HttpMetadata;
import com.norconex.collector.http.fetch.AbstractHttpFetcher;
import com.norconex.collector.http.fetch.HttpFetchResponse;
import com.norconex.collector.http.fetch.impl.WebDriverHttpAdapter.DriverResponseFilter;
import com.norconex.commons.lang.SLF4JUtil;
import com.norconex.commons.lang.Sleeper;
import com.norconex.commons.lang.io.CachedStreamFactory;
import com.norconex.commons.lang.xml.XML;

/**
 * <p>
 * Uses Selenium WebDriver support for using native browsers to crawl documents.
 * Useful for crawling JavaScript-driven websites.  To prevent launching a new
 * browser at each requests and to help maintain web sessions a browser
 * instance is started as a service for the life-duration of the crawler.
 * </p>
 *
 * <h3>Considerations</h3>
 * <p>
 * Relying on an external software to fetch pages can be slower and not as
 * scalable and may be less stable. Downloading of binaries and non-HTML file
 * format may not always be possible. The use of {@link GenericHttpFetcher}
 * should be preferred whenever possible. Use at your own risk.
 * </p>
 *
 * <h3>HTTP Headers</h3>
 * <p>
 * By default, web drivers do not expose HTTP headers.  If you want to
 * capture them, configure the "httpAdapter". A proxy service
 * will be started to monitor HTTP traffic and store HTTP headers.
 * </p>
 * <p>
 * <b>NOTE:</b> Capturing headers with a proxy may not be supported by all
 * Browsers/WebDriver implementations (e.g. Edge browser).
 * </p>
 *
 * <h3>XML configuration usage:</h3>
 *
 * <pre>
 *  &lt;fetcher class="com.norconex.collector.http.fetch.impl.WebDriverHttpFetcher"&gt;
 *
 *      &lt;browser&gt;[chrome|edge|firefox|opera|safari]&lt;/browser&gt;
 *      &lt;browserPath&gt;(browser executable or blank to detect)&lt;/browserPath&gt;
 *      &lt;driverPath&gt;(driver executable or blank to detect)&lt;/driverPath&gt;
 *      &lt;servicePort&gt;(default is 0 = random free port)&lt;/servicePort&gt;
 *
 *      &lt;!-- Optionally setup an HTTP proxy that allows to set and capture
 *           HTTP headers. For advanced use only. Not recommended
 *           for regular usage. --&gt;
 *      &lt;httpAdapter&gt;
 *          &lt;port&gt;(default is 0 = random free port)"&lt;/port&gt;
 *          &lt;userAgent&gt;(optionally overwrite browser user agent)&lt;/userAgent&gt;
 *
 *          &lt;!-- Optional HTTP request headers passed on every HTTP requests --&gt;
 *          &lt;headers&gt;
 *              &lt;header name="(header name)"&gt;(header value)&lt;/header&gt;
 *              &lt;!-- You can repeat this header tag as needed. --&gt;
 *          &lt;/headers&gt;
 *      &lt;/httpAdapter&gt;
 *
 *      &lt;!-- Optional browser capabilities supported by the web driver. --&gt;
 *      &lt;capabilities&gt;
 *          &lt;capability name="(capability name)"&gt;(capability value)&lt;/capability&gt;
 *          &lt;!-- multiple "capability" tags allowed --&gt;
 *      &lt;/capabilities&gt;
 *
 *      &lt;!-- Optionally take screenshots of each web pages. --&gt;
 *      &lt;screenshot&gt;
 *          &lt;capability name="(capability name)"&gt;(capability value)&lt;/capability&gt;
 *          &lt;!-- multiple "capability" tags allowed --&gt;
 *      &lt;/screenshot&gt;
 *
 *      &lt;restrictions&gt;
 *          &lt;restrictTo caseSensitive="[false|true]"
 *                  field="(name of metadata field name to match)"&gt;
 *              (regular expression of value to match)
 *          &lt;/restrictTo&gt;
 *          &lt;!-- multiple "restrictTo" tags allowed (only one needs to match) --&gt;
 *      &lt;/restrictions&gt;
 *
 *  &lt;/fetcher&gt;
 * </pre>
 *
 * <h4>Usage example:</h4>
 * <p>This example will use Firefox to crawl dynamically generated pages using
 * a specific web driver.
 * </p>
 * <pre>
 *  &lt;fetcher class="com.norconex.collector.http.fetch.impl.WebDriverHttpFetcher"&gt;
 *      &lt;browser&gt;firefox&lt;/browser&gt;
 *      &lt;driverPath&gt;/drivers/geckodriver.exe&lt;/driverPath&gt;
 *      &lt;restrictions&gt;
 *          &lt;restrictTo field="document.reference"&gt;.*dynamic.*$&lt;/restrictTo&gt;
 *      &lt;/restrictions&gt;
 *  &lt;/fetcher&gt;
 * </pre>
 *
 * @author Pascal Essiembre
 * @since 3.0.0
 */
//TODO implement CollectorLifeCycleListener instead? to ensure one per coll.?
public class WebDriverHttpFetcher extends AbstractHttpFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(
            WebDriverHttpFetcher.class);



    private CachedStreamFactory streamFactory;

    private WebDriverBrowser browser = WebDriverBrowser.FIREFOX;
    private Path driverPath;
    private Path browserPath;
    private String userAgent;
    private int servicePort;   // default is 0 = any free port

    private WebDriverHttpAdapter httpAdapter;
    private WebDriverHttpAdapterConfig httpAdapterConfig;
    private WebDriverScreenshotHandler screenshotHandler;

    private final MutableCapabilities capabilities = new MutableCapabilities();

    private Dimension windowSize;

    //TODO add script support

    private DriverService service;
    private final ThreadLocal<WebDriver> driverTL = new ThreadLocal<>();

    public WebDriverHttpFetcher() {
        super();
    }

    public WebDriverBrowser getBrowser() {
        return browser;
    }
    public void setBrowser(WebDriverBrowser driverName) {
        this.browser = driverName;
    }

    // Default will try to detect driver installation on OS
    public Path getDriverPath() {
        return driverPath;
    }
    public void setDriverPath(Path driverPath) {
        this.driverPath = driverPath;
    }

    // Default will try to detect browser installation on OS
    public Path getBrowserPath() {
        return browserPath;
    }
    public void setBrowserPath(Path binaryPath) {
        this.browserPath = binaryPath;
    }

    public int getServicePort() {
        return servicePort;
    }

    public void setServicePort(int servicePort) {
        this.servicePort = servicePort;
    }

    @Override
    public String getUserAgent() {
        return userAgent;
    }
    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public WebDriverHttpAdapterConfig getHttpAdapterConfig() {
        return httpAdapterConfig;
    }

    public void setHttpAdapterConfig(
            WebDriverHttpAdapterConfig httpAdapterConfig) {
        this.httpAdapterConfig = httpAdapterConfig;
    }

    public MutableCapabilities getCapabilities() {
        return capabilities;
    }

    public WebDriverScreenshotHandler getScreenshotHandler() {
        return screenshotHandler;
    }
    public void setScreenshotHandler(
            WebDriverScreenshotHandler screenshotHandler) {
        this.screenshotHandler = screenshotHandler;
    }

    public Dimension getWindowSize() {
        return windowSize;
    }
    public void setWindowSize(Dimension windowSize) {
        this.windowSize = windowSize;
    }



    @Override
    protected void crawlerStartup(CrawlerEvent<Crawler> event) {
        LOG.info("Starting {} driver service...", browser);
        System.setProperty("bmp.allowNativeDnsFallback", "true");

        if (event != null) {
            streamFactory = event.getSource().getStreamFactory();
        } else {
            streamFactory = new CachedStreamFactory();
        }

        //TODO remove support for EDGE since it is so limited???
        if (browser == WebDriverBrowser.EDGE) {
            LOG.warn("Using Microsoft Edge is not recommended if you want "
                   + "to capture HTTP headers, use multiple threads, be "
                   + "fully headless, or change its 'User-Agent'.");
        }

        try {
            MutableCapabilities options =
                    browser.createCapabilities(browserPath);

            initWebDriverLogging(options);

            options.setCapability(CapabilityType.ACCEPT_SSL_CERTS, true);
            options.setCapability(CapabilityType.ACCEPT_INSECURE_CERTS, true);

            options.merge(capabilities);

            if (httpAdapterConfig != null) {
                httpAdapter = new WebDriverHttpAdapter();
                httpAdapter.start(options, httpAdapterConfig);
                userAgent = httpAdapterConfig.getUserAgent();
            }

            Builder<?,?> serviceBuilder = browser.createServiceBuilder();
            if (driverPath != null) {
                serviceBuilder.usingDriverExecutable(driverPath.toFile());
            }
            service = serviceBuilder.usingPort(servicePort).build();
            service.start();


//            driver.set(new ChromeDriver((ChromeDriverService) service, options));
            WebDriver driver = new RemoteWebDriver(service.getUrl(), options);
            driverTL.set(driver);


//            driver.get().manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);


            if (windowSize != null) {
                driver.manage().window().setSize(
                        new org.openqa.selenium.Dimension(
                                windowSize.width, windowSize.height));
            }

            if (StringUtils.isBlank(userAgent)) {
                userAgent = (String) ((JavascriptExecutor) driver).executeScript(
                        "return navigator.userAgent;");
            }
            //TEST:
            //driver.manage().timeouts().pageLoadTimeout(3, TimeUnit.SECONDS);

        } catch (IOException e) {
            //TODO if exception, check if driver and browser were specified
            // either explicitly, as system property, or in PATH?
            throw new CollectorException(
                    "Could not start " + browser + " driver service.", e);
        }
    }

    private void initWebDriverLogging(MutableCapabilities capabilities) {
        LoggingPreferences logPrefs = new LoggingPreferences();
        Level level = SLF4JUtil.toJavaLevel(SLF4JUtil.getLevel(LOG));
        logPrefs.enable(LogType.PERFORMANCE, level);
        logPrefs.enable(LogType.PROFILER, level);
        logPrefs.enable(LogType.BROWSER, level);
        logPrefs.enable(LogType.CLIENT, level);
        logPrefs.enable(LogType.DRIVER, level);
        logPrefs.enable(LogType.SERVER, level);
        capabilities.setCapability(CapabilityType.LOGGING_PREFS, logPrefs);
    }

    @Override
    protected void crawlerShutdown(CrawlerEvent<Crawler> event) {
        LOG.info("Shutting down {} driver service...", browser);

        Sleeper.sleepSeconds(5);

        if (driverTL.get() != null) {
            driverTL.get().quit();
            driverTL.remove();
        }

        if (service != null && service.isRunning()) {
            service.stop();
        }
        if (httpAdapter != null) {
            httpAdapter.stop();
        }
    }

    @Override
    public HttpFetchResponse fetchHeaders(
            String url, HttpMetadata httpHeaders) {
        //TODO rely on proxy request filter to transform to a HEAD request?
        return new HttpFetchResponse(CrawlState.UNSUPPORTED, -1,
                "Headers can only be retrived with #fetchDocument instead, "
                + "when driverProxyEnabled is true.");
    }

    @Override
    public HttpFetchResponse fetchDocument(HttpDocument doc) {

	    LOG.debug("Fetching document: {}", doc.getReference());

	    if (httpAdapter != null) {
	        httpAdapter.bind(doc.getReference());
	    }

        doc.setInputStream(
                fetchDocumentContent(driverTL.get(), doc.getReference()));

        HttpFetchResponse response = resolveDriverResponse(doc);

        if (screenshotHandler != null) {
            screenshotHandler.takeScreenshot(driverTL.get(), doc);
        }

//      performDetection(doc);

        if (response != null) {
            return response;
        }
        return new HttpFetchResponse(HttpCrawlState.NEW, 200,
                "No exception thrown, but real status code unknown. "
              + "Capture headers for real status code.");
    }

    // Overwrite to perform more advanced configuration/manipulation.
    // thread-safe
    protected InputStream fetchDocumentContent(WebDriver driver, String url) {
        driver.get(url);
//      Sleeper.sleepSeconds(3); // Let the user actually see something!
        String pageSource = driver.getPageSource();
        return IOUtils.toInputStream(pageSource, StandardCharsets.UTF_8);
    }

    private HttpFetchResponse resolveDriverResponse(HttpDocument doc) {
        HttpFetchResponse response = null;
        if (httpAdapter != null) {
            DriverResponseFilter driverResponseFilter = httpAdapter.unbind();
            if (driverResponseFilter != null) {
                for (Entry<String, String> en : driverResponseFilter.getHeaders()) {
                    doc.getMetadata().add(en.getKey(), en.getValue());
                }
                response = toFetchResponse(driverResponseFilter);
            }
        }
        return response;
    }

    private HttpFetchResponse toFetchResponse(DriverResponseFilter driverResponseFilter) {
        HttpFetchResponse response = null;
        if (driverResponseFilter != null) {
            //TODO validate status code
            int statusCode = driverResponseFilter.getStatusCode();
            String reason = driverResponseFilter.getReasonPhrase();
            if (statusCode >= 200 && statusCode < 300) {
                response = new HttpFetchResponse(
                        HttpCrawlState.NEW, statusCode, reason);
            } else {
                response = new HttpFetchResponse(
                        CrawlState.BAD_STATUS, statusCode, reason);
            }
        }
        return response;
    }



    @Override
    public void loadHttpFetcherFromXML(XML xml) {
        setBrowser(xml.getEnum("browser", WebDriverBrowser.class, browser));
        setDriverPath(xml.getPath("driverPath", driverPath));
        setBrowserPath(xml.getPath("browserPath", browserPath));
        setUserAgent(xml.getString("userAgent", userAgent));
        setServicePort(xml.getInteger("servicePort", servicePort));

        xml.getXML("httpAdapter").ifDefined(x -> {
            WebDriverHttpAdapterConfig cfg = new WebDriverHttpAdapterConfig();
            cfg.loadFromXML(x);
            setHttpAdapterConfig(cfg);
        });

        xml.getXML("screenshot").ifDefined(x -> {
            WebDriverScreenshotHandler h =
                    new WebDriverScreenshotHandler(streamFactory);
            h.loadFromXML(x);
            setScreenshotHandler(h);
        });

        for (Entry<String, String> en : xml.getStringMap(
                "capabilities/capability", "@name", ".").entrySet()) {
            getCapabilities().setCapability(en.getKey(), en.getValue());
        }
    }
    @Override
    public void saveHttpFetcherToXML(XML xml) {
        xml.addElement("browser", browser);
        xml.addElement("driverPath", driverPath);
        xml.addElement("browserPath", browserPath);
        xml.addElement("userAgent", userAgent);
        xml.addElement("servicePort", servicePort);

        if (httpAdapterConfig != null) {
            httpAdapterConfig.saveToXML(xml.addElement("httpAdapter"));
        }

        if (screenshotHandler != null) {
            screenshotHandler.saveToXML(xml.addElement("screenshot"));
        }

        XML capabXml = xml.addElement("capabilities");
        for (Entry<String, Object> en : capabilities.asMap().entrySet()) {
            capabXml.addElement("capability",
                    en.getValue()).setAttribute("name", en.getKey());
        }
    }

    @Override
    public boolean equals(final Object other) {
        return EqualsBuilder.reflectionEquals(this, other);
    }
    @Override
    public int hashCode() {
        return HashCodeBuilder.reflectionHashCode(this);
    }
    @Override
    public String toString() {
        return new ReflectionToStringBuilder(
                this, ToStringStyle.SHORT_PREFIX_STYLE).toString();
    }



    //TODO several things:
    // - Support complex configuration via JavaScript?  Like authenticating
    //   scripts populating fields and submitting pages.


}