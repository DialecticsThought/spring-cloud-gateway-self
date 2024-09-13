/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.handler;

import java.util.function.Function;

import reactor.core.publisher.Mono;

import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DIFFERENT;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.DISABLED;
import static org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping.ManagementPortType.SAME;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_HANDLER_MAPPER_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_PREDICATE_ROUTE_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REACTOR_CONTEXT_ATTR;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

/**
 * @author Spencer Gibb
 */
public class RoutePredicateHandlerMapping extends AbstractHandlerMapping {

	private final FilteringWebHandler webHandler;

	private final RouteLocator routeLocator;

	private final Integer managementPort;

	private final ManagementPortType managementPortType;

	public RoutePredicateHandlerMapping(FilteringWebHandler webHandler, RouteLocator routeLocator,
										GlobalCorsProperties globalCorsProperties, Environment environment) {
		this.webHandler = webHandler;
		this.routeLocator = routeLocator;

		this.managementPort = getPortProperty(environment, "management.server.");
		this.managementPortType = getManagementPortType(environment);
		setOrder(environment.getProperty(GatewayProperties.PREFIX + ".handler-mapping.order", Integer.class, 1));
		setCorsConfigurations(globalCorsProperties.getCorsConfigurations());
	}

	private ManagementPortType getManagementPortType(Environment environment) {
		Integer serverPort = getPortProperty(environment, "server.");
		if (this.managementPort != null && this.managementPort < 0) {
			return DISABLED;
		}
		return ((this.managementPort == null || (serverPort == null && this.managementPort.equals(8080))
				|| (this.managementPort != 0 && this.managementPort.equals(serverPort))) ? SAME : DIFFERENT);
	}

	private static Integer getPortProperty(Environment environment, String prefix) {
		return environment.getProperty(prefix + "port", Integer.class);
	}

	/**
	 * TODO 重点
	 *
	 * @param exchange
	 * @return
	 */
	@Override
	protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
		// don't handle requests on management port if set and different than server port
		if (this.managementPortType == DIFFERENT && this.managementPort != null
				&& exchange.getRequest().getLocalAddress() != null
				&& exchange.getRequest().getLocalAddress().getPort() == this.managementPort) {
			return Mono.empty();
		}
		exchange.getAttributes().put(GATEWAY_HANDLER_MAPPER_ATTR, getSimpleName());

		return Mono.deferContextual(contextView -> {
			exchange.getAttributes().put(GATEWAY_REACTOR_CONTEXT_ATTR, contextView);
			// 核心方法是lookupRoute(exchange)，这里会去进行路由的校验，根据我们配置文件中定义的路由断言规则进行校验
			// TODO 进入lookupRoute
			return lookupRoute(exchange)
					// .log("route-predicate-handler-mapping", Level.FINER) //name this
					.flatMap((Function<Route, Mono<?>>) r -> {
						// 下面几行代码就是操作exchange的属性
						// 上方的lookupRoute()方法中会添加GATEWAY_PREDICATE_ROUTE_ATTR，这里就进行移除
						exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
						if (logger.isDebugEnabled()) {
							logger.debug("Mapping [" + getExchangeDesc(exchange) + "] to " + r);
						}
						// 把当前路由对象添加进exchange对象中，之后的流程还会用到我们的路由对象
						exchange.getAttributes().put(GATEWAY_ROUTE_ATTR, r);
						// 路由匹配成功，就直接返回WebHandler对象
						return Mono.just(webHandler);
					}).switchIfEmpty(Mono.empty().then(Mono.fromRunnable(() -> {
						// 当前请求没有任何一个路由匹配上的处理流程
						exchange.getAttributes().remove(GATEWAY_PREDICATE_ROUTE_ATTR);
						if (logger.isTraceEnabled()) {
							logger.trace("No RouteDefinition found for [" + getExchangeDesc(exchange) + "]");
						}
					})));
		});
	}

	@Override
	protected CorsConfiguration getCorsConfiguration(Object handler, ServerWebExchange exchange) {
		// TODO: support cors configuration via properties on a route see gh-229
		// see RequestMappingHandlerMapping.initCorsConfiguration()
		// also see
		// https://github.com/spring-projects/spring-framework/blob/master/spring-web/src/test/java/org/springframework/web/cors/reactive/CorsWebFilterTests.java
		return super.getCorsConfiguration(handler, exchange);
	}

	// TODO: get desc from factory?
	private String getExchangeDesc(ServerWebExchange exchange) {
		StringBuilder out = new StringBuilder();
		out.append("Exchange: ");
		out.append(exchange.getRequest().getMethod());
		out.append(" ");
		out.append(exchange.getRequest().getURI());
		return out.toString();
	}

	protected Mono<Route> lookupRoute(ServerWebExchange exchange) {
		// 获取到我们yml配置文件中所有定义的路由，并进行遍历
		return this.routeLocator.getRoutes()
				// individually filter routes so that filterWhen error delaying is not a
				// problem
				.concatMap(route -> Mono.just(route).filterWhen(r -> {
							// add the current route we are testing
							// 添加GATEWAY_PREDICATE_ROUTE_ATTR
							exchange.getAttributes().put(GATEWAY_PREDICATE_ROUTE_ATTR, r.getId());
							/**
							 * 调用当前路由对象中的断言的apply()方法，apply()方法中又是一些异步的处理流程
							 * TODO 这里就会根据我们配置文件中为路由配置的各个断言，去调用各个断言对象
							 * AsyncPredicate有多个实现类
							 * eg:
							 * 1.DefaultAsyncPredicate<T>
							 *     他的apply方法是 Publisher<Boolean> apply(T t) {return Mono.just(delegate.test(t));}
							 * 2.AndAsyncPredicate
							 * AndAsyncPredicate将所有的 Predicate 分为两部分：left 和 right，
							 * 而每一部分的 Predicate 又都被 AsyncPredicate 包装。
							 * 调用 Predicate.apply() 方法做谓词匹配时，会分别调用 left 和 right 的 apply() 方法；
							 * left#apply() 方法是一个递归操作，递归直到 left 中仅包含一个 Predicate 时，再往上返回。
							 *
							 *
							 * TODO 这里 以 去看path路径匹配的断言类PathRoutePredicateFactory 为例子
							 * 先查看AsyncPredicate 的apply方法
							 * 1.调用到 DefaultAsyncPredicate的
							 *   Publisher<Boolean> apply(T t) {return Mono.just(delegate.test(t));}
							 * 2.有调用到 GatewayPredicate#test 这个方法会做具体的路径匹配
							 */
							return r.getPredicate().apply(exchange);
						})
						// instead of immediately stopping main flux due to error, log and
						// swallow it
						.doOnError(e -> logger.error("Error applying predicate for route: " + route.getId(), e))
						.onErrorResume(e -> Mono.empty()))
				// .defaultIfEmpty() put a static Route not found
				// or .switchIfEmpty()
				// .switchIfEmpty(Mono.<Route>empty().log("noroute"))
				.next()
				// TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("Route matched: " + route.getId());
					}
					validateRoute(route, exchange);
					return route;
				});

		/*
		 * TODO: trace logging if (logger.isTraceEnabled()) {
		 * logger.trace("RouteDefinition did not match: " + routeDefinition.getId()); }
		 */
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>
	 * The default implementation is empty. Can be overridden in subclasses, for example
	 * to enforce specific preconditions expressed in URL mappings.
	 *
	 * @param route    the Route object to validate
	 * @param exchange current exchange
	 * @throws Exception if validation failed
	 */
	@SuppressWarnings("UnusedParameters")
	protected void validateRoute(Route route, ServerWebExchange exchange) {
	}

	protected String getSimpleName() {
		return "RoutePredicateHandlerMapping";
	}

	public enum ManagementPortType {

		/**
		 * The management port has been disabled.
		 */
		DISABLED,

		/**
		 * The management port is the same as the server port.
		 */
		SAME,

		/**
		 * The management port and server port are different.
		 */
		DIFFERENT;

	}

}
