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

import org.openqa.selenium.firefox.FirefoxOptions;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class FirefoxWebDriverHttpFetcherTest
        extends AbstractWebDriverHttpFetcherTest  {

    @Container
    private final BrowserWebDriverContainer<?> browser =
            createWebDriverContainer(new FirefoxOptions());

    @Override
    protected BrowserWebDriverContainer<?> getBrowserDriver() {
        return browser;
    }
    @Override
    protected Browser getBrowserType() {
        return Browser.FIREFOX;
    }
}
