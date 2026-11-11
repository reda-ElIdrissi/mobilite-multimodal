package com.transport.autopartage.vehiculeRoute;

import com.transport.mobility.operations.OperationsConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Import(OperationsConfiguration.class)
@EnableScheduling
class CarOperationConfiguration {}
