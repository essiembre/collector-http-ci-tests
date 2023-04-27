/* Copyright 2021-2022 Norconex Inc.
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
package com.norconex.crawler.core.filter.impl;

import org.apache.commons.lang3.StringUtils;

import com.norconex.commons.lang.map.Properties;
import com.norconex.commons.lang.map.PropertyMatcher;
import com.norconex.commons.lang.text.TextMatcher;
import com.norconex.commons.lang.xml.XML;
import com.norconex.commons.lang.xml.XMLConfigurable;
import com.norconex.crawler.core.filter.DocumentFilter;
import com.norconex.crawler.core.filter.MetadataFilter;
import com.norconex.importer.doc.Doc;
import com.norconex.importer.handler.filter.OnMatch;
import com.norconex.importer.handler.filter.OnMatchFilter;

import lombok.EqualsAndHashCode;
import lombok.ToString;
/**
 * <p>
 * Accepts or rejects a reference based on whether one or more
 * metadata field values are matching.
 * </p>
 *
 * {@nx.xml.usage
 * <filter class="com.norconex.crawler.core.filter.impl.GenericMetadataFilter"
 *     onMatch="[include|exclude]">
 *   <fieldMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Expression matching one or more fields to evaluate.)
 *   </fieldMatcher>
 *   <valueMatcher {@nx.include com.norconex.commons.lang.text.TextMatcher#matchAttributes}>
 *     (Expression matching one or more values from matching fields.)
 *   </valueMatcher>
 * </filter>
 * }
 *
 * {@nx.xml.example
 * <filter class="GenericMetadataFilter" onMatch="exclude">
 *   <fieldMatcher>Content-Type</fieldMatcher>
 *   <valueMatcher>application/zip</valueMatcher>
 * </filter>
 * }
 * <p>
 * Used in a web context, the above example filters out Zip documents base
 * on a "Content-Type" metadata field.
 * </p>
 *
 */
@SuppressWarnings("javadoc")
@EqualsAndHashCode
@ToString
public class GenericMetadataFilter implements OnMatchFilter, MetadataFilter,
        DocumentFilter, XMLConfigurable {

    private OnMatch onMatch;
    private final TextMatcher fieldMatcher = new TextMatcher();
    private final TextMatcher valueMatcher = new TextMatcher();

    public GenericMetadataFilter() {
        this(null, null, OnMatch.INCLUDE);
    }
    public GenericMetadataFilter(
            TextMatcher fieldMatcher, TextMatcher valueMatcher) {
        this(fieldMatcher, valueMatcher, OnMatch.INCLUDE);
    }
    public GenericMetadataFilter(
            TextMatcher fieldMatcher,
            TextMatcher valueMatcher,
            OnMatch onMatch) {
        setFieldMatcher(fieldMatcher);
        setValueMatcher(valueMatcher);
        setOnMatch(onMatch);
    }

    @Override
    public OnMatch getOnMatch() {
        return onMatch;
    }
    public void setOnMatch(OnMatch onMatch) {
        this.onMatch = onMatch;
    }

    /**
     * Gets the field matcher.
     * @return field matcher
     */
    public TextMatcher getFieldMatcher() {
        return fieldMatcher;
    }
    /**
     * Sets the field matcher.
     * @param fieldMatcher field matcher
     */
    public void setFieldMatcher(TextMatcher fieldMatcher) {
        this.fieldMatcher.copyFrom(fieldMatcher);
    }
    /**
     * Gets the value matcher.
     * @return value matcher
     */
    public TextMatcher getValueMatcher() {
        return valueMatcher;
    }
    /**
     * Sets the value matcher.
     * @param valueMatcher value matcher
     */
    public void setValueMatcher(TextMatcher valueMatcher) {
        this.valueMatcher.copyFrom(valueMatcher);
    }

    @Override
    public boolean acceptMetadata(String reference, Properties metadata) {
        if (StringUtils.isBlank(fieldMatcher.getPattern())
                || StringUtils.isBlank(valueMatcher.getPattern())
                || new PropertyMatcher(
                        fieldMatcher, valueMatcher).matches(metadata)) {
            return getOnMatch() == OnMatch.INCLUDE;
        }
        return getOnMatch() == OnMatch.EXCLUDE;
    }

    @Override
    public boolean acceptDocument(Doc document) {
        if (document == null) {
            return getOnMatch() == OnMatch.INCLUDE;
        }
        return acceptMetadata(document.getReference(), document.getMetadata());
    }

    @Override
    public void loadFromXML(XML xml) {
        setOnMatch(xml.getEnum("@onMatch", OnMatch.class, onMatch));
        fieldMatcher.loadFromXML(xml.getXML("fieldMatcher"));
        valueMatcher.loadFromXML(xml.getXML("valueMatcher"));
    }
    @Override
    public void saveToXML(XML xml) {
        xml.setAttribute("onMatch", onMatch);
        fieldMatcher.saveToXML(xml.addElement("fieldMatcher"));
        valueMatcher.saveToXML(xml.addElement("valueMatcher"));
    }
}
