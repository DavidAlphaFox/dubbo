/*
 * Copyright 1999-2012 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.config.AbstractConfig;
import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ConsumerConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.ReferenceConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.dubbo.config.annotation.Service;

/**
 * AnnotationBean
 * 
 * @author william.liangf
 * @export
 */
// DisposableBean
// Interface to be implemented by beans that want to release resources on destruction.
// A BeanFactory is supposed to invoke the destroy method if it disposes a cached singleton.
// An application context is supposed to dispose all of its singletons on close.
// BeanFactoryPostProcessor
// Allows for custom modification of an application context's bean definitions,
// adapting the bean property values of the context's underlying bean factory.
// Application contexts can auto-detect BeanFactoryPostProcessor beans in
// their bean definitions and apply them before any other beans get created.
// BeanPostProcessor
// Factory hook that allows for custom modification of new bean instances,
// e.g. checking for marker interfaces or wrapping them with proxies.
// ApplicationContextAware
// Interface to be implemented by any object that wishes to be notified of the ApplicationContext that it runs in.
// Implementing this interface makes sense for example when an object requires access to a set of collaborating beans.
// Note that configuration via bean references is preferable to implementing this interface just for bean lookup purposes.
public class AnnotationBean extends AbstractConfig implements DisposableBean,
        BeanFactoryPostProcessor, BeanPostProcessor, ApplicationContextAware {

    private static final long serialVersionUID = -7582802454287589552L;

    private static final Logger logger = LoggerFactory.getLogger(Logger.class);

    private String annotationPackage;

    private String[] annotationPackages;

    private final Set<ServiceConfig<?>> serviceConfigs = new ConcurrentHashSet<ServiceConfig<?>>();

    private final ConcurrentMap<String, ReferenceBean<?>> referenceConfigs = new ConcurrentHashMap<String, ReferenceBean<?>>();

    public String getPackage() {
        return annotationPackage;
    }

    public void setPackage(String annotationPackage) {
        this.annotationPackage = annotationPackage;
        this.annotationPackages = (annotationPackage == null || annotationPackage.length() == 0) ? null
                : Constants.COMMA_SPLIT_PATTERN.split(annotationPackage);
    }

    private ApplicationContext applicationContext;

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory)
            throws BeansException {
        if (annotationPackage == null || annotationPackage.length() == 0) {
            return;
        }
        if (beanFactory instanceof BeanDefinitionRegistry) {
            try {
                // init scanner
                Class<?> scannerClass = ReflectUtils.forName("org.springframework.context.annotation.ClassPathBeanDefinitionScanner");
                Object scanner = scannerClass.getConstructor(new Class<?>[] {BeanDefinitionRegistry.class, boolean.class}).newInstance(new Object[] {(BeanDefinitionRegistry) beanFactory, true});
                // add filter
                Class<?> filterClass = ReflectUtils.forName("org.springframework.core.type.filter.AnnotationTypeFilter");
                Object filter = filterClass.getConstructor(Class.class).newInstance(Service.class);
                Method addIncludeFilter = scannerClass.getMethod("addIncludeFilter", ReflectUtils.forName("org.springframework.core.type.filter.TypeFilter"));
                addIncludeFilter.invoke(scanner, filter);
                // scan packages
                String[] packages = Constants.COMMA_SPLIT_PATTERN.split(annotationPackage);
                Method scan = scannerClass.getMethod("scan", new Class<?>[]{String[].class});
                scan.invoke(scanner, new Object[] {packages});
            } catch (Throwable e) {
                // spring 2.0
            }
        }
    }

    public void destroy() throws Exception {
        for (ServiceConfig<?> serviceConfig : serviceConfigs) {
            try {
                serviceConfig.unexport();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
        for (ReferenceConfig<?> referenceConfig : referenceConfigs.values()) {
            try {
                referenceConfig.destroy();
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
    // 实现BeanPostProcessor的接口
    // 在Bean被创建后，进行初始化后的调用
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        if (! isMatchPackage(bean)) {
            return bean;
        }
        Service service = bean.getClass().getAnnotation(Service.class);
        // 如果被标记为service
        if (service != null) {
            // 创建新的ServiceBean
            ServiceBean<Object> serviceConfig = new ServiceBean<Object>(service);
            if (void.class.equals(service.interfaceClass())
                    && "".equals(service.interfaceName())) {
                // 在service注解都没有设置名称的时候
                if (bean.getClass().getInterfaces().length > 0) {
                    // 如果我们从bean上能得到Class所实现的interface的时候
                    // 我们需要将serviceConfig设置inteface
                    serviceConfig.setInterface(bean.getClass().getInterfaces()[0]);
                } else {
                    throw new IllegalStateException("Failed to export remote service class " + bean.getClass().getName() + ", cause: The @Service undefined interfaceClass or interfaceName, and the service class unimplemented any interfaces.");
                }
            }
            if (applicationContext != null) {
                serviceConfig.setApplicationContext(applicationContext);
                if (service.registry() != null && service.registry().length > 0) {
                    List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                    for (String registryId : service.registry()) {
                        if (registryId != null && registryId.length() > 0) {
                            registryConfigs.add((RegistryConfig)applicationContext.getBean(registryId, RegistryConfig.class));
                        }
                    }
                    serviceConfig.setRegistries(registryConfigs);
                }
                if (service.provider() != null && service.provider().length() > 0) {
                    serviceConfig.setProvider((ProviderConfig)applicationContext.getBean(service.provider(),ProviderConfig.class));
                }
                if (service.monitor() != null && service.monitor().length() > 0) {
                    serviceConfig.setMonitor((MonitorConfig)applicationContext.getBean(service.monitor(), MonitorConfig.class));
                }
                if (service.application() != null && service.application().length() > 0) {
                    serviceConfig.setApplication((ApplicationConfig)applicationContext.getBean(service.application(), ApplicationConfig.class));
                }
                if (service.module() != null && service.module().length() > 0) {
                    serviceConfig.setModule((ModuleConfig)applicationContext.getBean(service.module(), ModuleConfig.class));
                }
                if (service.provider() != null && service.provider().length() > 0) {
                    serviceConfig.setProvider((ProviderConfig)applicationContext.getBean(service.provider(), ProviderConfig.class));
                } else {
                    
                }
                if (service.protocol() != null && service.protocol().length > 0) {
                    List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                    for (String protocolId : service.registry()) {
                        if (protocolId != null && protocolId.length() > 0) {
                            protocolConfigs.add((ProtocolConfig)applicationContext.getBean(protocolId, ProtocolConfig.class));
                        }
                    }
                    serviceConfig.setProtocols(protocolConfigs);
                }
                try {
                    serviceConfig.afterPropertiesSet();
                } catch (RuntimeException e) {
                    throw (RuntimeException) e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            serviceConfig.setRef(bean);
            serviceConfigs.add(serviceConfig);
            serviceConfig.export();
        }
        return bean;
    }
    // 实现BeanPostProcessor的接口
    // 在Bean被创建后，进行初始化前进行的调用
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        if (! isMatchPackage(bean)) {
            return bean;
        }
        // 得到当前Bean所有的方法
        Method[] methods = bean.getClass().getMethods();
        for (Method method : methods) {
            String name = method.getName();
            // 按照一定的规则检查方法名字
            if (name.length() > 3 && name.startsWith("set")
                    && method.getParameterTypes().length == 1
                    && Modifier.isPublic(method.getModifiers())
                    && ! Modifier.isStatic(method.getModifiers())) {
                try {
                    // 方法上是否有Reference注解
                	Reference reference = method.getAnnotation(Reference.class);
                	if (reference != null) {
	                	Object value = refer(reference, method.getParameterTypes()[0]);
                        // value 不为null的时候，
                        // 直接invoke
	                	if (value != null) {
	                		method.invoke(bean, new Object[] {  });
	                	}
                	}
                } catch (Throwable e) {
                    logger.error("Failed to init remote service reference at method " + name + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
                }
            }
        }
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            try {
                if (! field.isAccessible()) {
                    field.setAccessible(true);
                }
                Reference reference = field.getAnnotation(Reference.class);
            	if (reference != null) {
	                Object value = refer(reference, field.getType());
	                if (value != null) {
	                	field.set(bean, value);
	                }
            	}
            } catch (Throwable e) {
            	logger.error("Failed to init remote service reference at filed " + field.getName() + " in class " + bean.getClass().getName() + ", cause: " + e.getMessage(), e);
            }
        }
        return bean;
    }

    private Object refer(Reference reference, Class<?> referenceClass) { //method.getParameterTypes()[0]
        String interfaceName;
        if (! "".equals(reference.interfaceName())) {
            // 如果reference的interface的名字不为空
            // 获取interfaceName
            interfaceName = reference.interfaceName();
        } else if (! void.class.equals(reference.interfaceClass())) {
            // interface不是void
            // 通过interface的class来获取名字
            interfaceName = reference.interfaceClass().getName();
        } else if (referenceClass.isInterface()) {
            // 直接从参数中，获取interface的名字
            interfaceName = referenceClass.getName();
        } else {
            throw new IllegalStateException("The @Reference undefined interfaceClass or interfaceName, and the property type " + referenceClass.getName() + " is not a interface.");
        }
        // 创建Key
        String key = reference.group() + "/" + interfaceName + ":" + reference.version();
        ReferenceBean<?> referenceConfig = referenceConfigs.get(key);
        // 如果能得到相应的ReferenceBean
        if (referenceConfig == null) {
            // 创建新的ReferenceBean
            referenceConfig = new ReferenceBean<Object>(reference);
            if (void.class.equals(reference.interfaceClass())
                    && "".equals(reference.interfaceName())
                    && referenceClass.isInterface()) {
                // 当interfaceClass为空，interfaceName为空
                // 且referenceClass是inteface的时候
                // 设置referenceConfig的inteface
                referenceConfig.setInterface(referenceClass);
            }

            if (applicationContext != null) {
                // 当应用上下文applicationContext 不为空的时候
                // 我们需要设置应用上下文
                referenceConfig.setApplicationContext(applicationContext);
                if (reference.registry() != null && reference.registry().length > 0) {
                    // 如果reference的注册中心配置不为空
                    List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                    for (String registryId : reference.registry()) {
                        if (registryId != null && registryId.length() > 0) {
                            registryConfigs.add((RegistryConfig)applicationContext.getBean(registryId, RegistryConfig.class));
                        }
                    }
                    referenceConfig.setRegistries(registryConfigs);
                }
                if (reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig)applicationContext.getBean(reference.consumer(), ConsumerConfig.class));
                }
                if (reference.monitor() != null && reference.monitor().length() > 0) {
                    referenceConfig.setMonitor((MonitorConfig)applicationContext.getBean(reference.monitor(), MonitorConfig.class));
                }
                if (reference.application() != null && reference.application().length() > 0) {
                    referenceConfig.setApplication((ApplicationConfig)applicationContext.getBean(reference.application(), ApplicationConfig.class));
                }
                if (reference.module() != null && reference.module().length() > 0) {
                    referenceConfig.setModule((ModuleConfig)applicationContext.getBean(reference.module(), ModuleConfig.class));
                }
                if (reference.consumer() != null && reference.consumer().length() > 0) {
                    referenceConfig.setConsumer((ConsumerConfig)applicationContext.getBean(reference.consumer(), ConsumerConfig.class));
                }
                try {
                    referenceConfig.afterPropertiesSet();
                } catch (RuntimeException e) {
                    throw (RuntimeException) e;
                } catch (Exception e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            }
            referenceConfigs.putIfAbsent(key, referenceConfig);
            referenceConfig = referenceConfigs.get(key);
        }
        return referenceConfig.get();
    }

    private boolean isMatchPackage(Object bean) {
        if (annotationPackages == null || annotationPackages.length == 0) {
            return true;
        }
        String beanClassName = bean.getClass().getName();
        for (String pkg : annotationPackages) {
            if (beanClassName.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

}
