package com.transport.mobility.operations;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({OperationJournal.class, OperatorController.class})
public class OperationsConfiguration {
    public static final String[] WRITE_PATHS = {"/internal/v1/holds", "/internal/v1/holds/*/confirm",
            "/internal/v1/cancellations", "/internal/v1/operations/*"};
}
