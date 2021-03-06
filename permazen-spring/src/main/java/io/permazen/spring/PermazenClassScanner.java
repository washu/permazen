
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package io.permazen.spring;

import io.permazen.annotation.PermazenType;

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.core.env.Environment;
import org.springframework.core.type.ClassMetadata;

/**
 * Scans the classpath for types annotated as {@link PermazenType &#64;PermazenType}.
 */
public class PermazenClassScanner extends AnnotatedClassScanner {

    /**
     * Constructor.
     */
    public PermazenClassScanner() {
        super(PermazenType.class);
    }

    /**
     * Constructor.
     *
     * @param useDefaultFilters whether to register the default filters for {@link PermazenType &#64;PermazenType} type annotations
     * @param environment environment to use
     */
    public PermazenClassScanner(boolean useDefaultFilters, Environment environment) {
        super(useDefaultFilters, environment, PermazenType.class);
    }

    /**
     * Determine if the given bean definition is a possible search candidate.
     *
     * <p>
     * This method is overridden in {@link PermazenClassScanner} to allow abstract classes and interfaces.
     */
    @Override
    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
        final ClassMetadata metadata = beanDefinition.getMetadata();
        return metadata.isIndependent();
    }
}

