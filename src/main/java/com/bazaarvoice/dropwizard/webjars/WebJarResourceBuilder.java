package com.bazaarvoice.dropwizard.webjars;

import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.List;

public class WebJarResourceBuilder {
    private String cacheSpec = null;
    private List<String> additionalPackages = Lists.newArrayList();

    public WebJarResource build() {
        return new WebJarResource(cacheSpec, additionalPackages);
    }

    public WebJarResourceBuilder withCacheSpec(String spec) {
        cacheSpec = spec;
        return this;
    }

    public WebJarResourceBuilder withAdditionalWebjarPackage(String additionalPackage) {
        return withAdditionalWebjarPackages(additionalPackage);
    }

    public WebJarResourceBuilder withAdditionalWebjarPackages(String... additionalPackages) {
        Collections.addAll(this.additionalPackages, additionalPackages);
        return this;
    }
}
