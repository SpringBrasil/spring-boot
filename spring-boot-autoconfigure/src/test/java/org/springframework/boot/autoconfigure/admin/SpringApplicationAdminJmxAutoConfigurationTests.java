/*
 * Copyright 2012-2017 the original author or authors.
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

package org.springframework.boot.autoconfigure.admin;

import java.lang.management.ManagementFactory;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectInstance;
import javax.management.ObjectName;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.admin.SpringApplicationAdminMXBeanRegistrar;
import org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.util.EnvironmentTestUtils;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

/**
 * Tests for {@link SpringApplicationAdminJmxAutoConfiguration}.
 *
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 */
public class SpringApplicationAdminJmxAutoConfigurationTests {

	private static final String ENABLE_ADMIN_PROP = "spring.application.admin.enabled=true";

	private static final String JMX_NAME_PROPERTY = "spring.application.admin.jmx-name";

	private static final String DEFAULT_JMX_NAME = "org.springframework.boot:type=Admin,name=SpringApplication";

	@Rule
	public final ExpectedException thrown = ExpectedException.none();

	private ConfigurableApplicationContext context;

	private MBeanServer mBeanServer;

	@Before
	public void setup() throws MalformedObjectNameException {
		this.mBeanServer = ManagementFactory.getPlatformMBeanServer();
	}

	@After
	public void tearDown() {
		if (this.context != null) {
			this.context.close();
		}
	}

	@Test
	public void notRegisteredByDefault()
			throws MalformedObjectNameException, InstanceNotFoundException {
		load();
		this.thrown.expect(InstanceNotFoundException.class);
		this.mBeanServer.getObjectInstance(createDefaultObjectName());
	}

	@Test
	public void registeredWithProperty() throws Exception {
		load(ENABLE_ADMIN_PROP);
		ObjectName objectName = createDefaultObjectName();
		ObjectInstance objectInstance = this.mBeanServer.getObjectInstance(objectName);
		assertThat(objectInstance).as("Lifecycle bean should have been registered")
				.isNotNull();
	}

	@Test
	public void registerWithCustomJmxName() throws InstanceNotFoundException {
		String customJmxName = "org.acme:name=FooBar";
		System.setProperty(JMX_NAME_PROPERTY, customJmxName);
		try {
			load(ENABLE_ADMIN_PROP);
			try {
				this.mBeanServer.getObjectInstance(createObjectName(customJmxName));
			}
			catch (InstanceNotFoundException ex) {
				fail("Admin MBean should have been exposed with custom name");
			}
			this.thrown.expect(InstanceNotFoundException.class); // Should not be exposed
			this.mBeanServer.getObjectInstance(createDefaultObjectName());
		}
		finally {
			System.clearProperty(JMX_NAME_PROPERTY);
		}
	}

	@Test
	public void registerWithSimpleWebApp() throws Exception {
		this.context = new SpringApplicationBuilder()
				.sources(ServletWebServerFactoryAutoConfiguration.class,
						DispatcherServletAutoConfiguration.class,
						JmxAutoConfiguration.class,
						SpringApplicationAdminJmxAutoConfiguration.class)
				.run("--" + ENABLE_ADMIN_PROP, "--server.port=0");
		assertThat(this.context).isInstanceOf(ServletWebServerApplicationContext.class);
		assertThat(this.mBeanServer.getAttribute(createDefaultObjectName(),
				"EmbeddedWebApplication")).isEqualTo(Boolean.TRUE);
		int expected = ((ServletWebServerApplicationContext) this.context).getWebServer()
				.getPort();
		String actual = getProperty(createDefaultObjectName(), "local.server.port");
		assertThat(actual).isEqualTo(String.valueOf(expected));
	}

	@Test
	public void onlyRegisteredOnceWhenThereIsAChildContext() throws Exception {
		SpringApplicationBuilder parentBuilder = new SpringApplicationBuilder()
				.web(WebApplicationType.NONE).sources(JmxAutoConfiguration.class,
						SpringApplicationAdminJmxAutoConfiguration.class);
		SpringApplicationBuilder childBuilder = parentBuilder
				.child(JmxAutoConfiguration.class,
						SpringApplicationAdminJmxAutoConfiguration.class)
				.web(WebApplicationType.NONE);

		try (ConfigurableApplicationContext parent = parentBuilder
				.run("--" + ENABLE_ADMIN_PROP);
				ConfigurableApplicationContext child = childBuilder
						.run("--" + ENABLE_ADMIN_PROP)) {
			BeanFactoryUtils.beanOfType(parent.getBeanFactory(),
					SpringApplicationAdminMXBeanRegistrar.class);
			this.thrown.expect(NoSuchBeanDefinitionException.class);
			BeanFactoryUtils.beanOfType(child.getBeanFactory(),
					SpringApplicationAdminMXBeanRegistrar.class);
		}
	}

	private ObjectName createDefaultObjectName() {
		return createObjectName(DEFAULT_JMX_NAME);
	}

	private ObjectName createObjectName(String jmxName) {
		try {
			return new ObjectName(jmxName);
		}
		catch (MalformedObjectNameException ex) {
			throw new IllegalStateException("Invalid jmx name " + jmxName, ex);
		}
	}

	private String getProperty(ObjectName objectName, String key) throws Exception {
		return (String) this.mBeanServer.invoke(objectName, "getProperty",
				new Object[] { key }, new String[] { String.class.getName() });
	}

	private void load(String... environment) {
		AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
		EnvironmentTestUtils.addEnvironment(applicationContext, environment);
		applicationContext.register(JmxAutoConfiguration.class,
				SpringApplicationAdminJmxAutoConfiguration.class);
		applicationContext.refresh();
		this.context = applicationContext;
	}

}
