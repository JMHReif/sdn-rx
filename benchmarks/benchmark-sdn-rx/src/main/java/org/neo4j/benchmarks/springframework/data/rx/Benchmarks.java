/*
 * Copyright (c) 2019 "Neo4j,"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.benchmarks.springframework.data.rx;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.neo4j.benchmarks.springframework.data.rx.app.Application;
import org.neo4j.benchmarks.springframework.data.rx.app.MovieRepository;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.Neo4jContainer;

@State(Scope.Benchmark)
public class Benchmarks {

	private static final String SYS_PROPERTY_NEOJ4_URL = "SDN_RX_NEO4J_URL";
	private static final String SYS_PROPERTY_NEO4J_PASSWORD = "SDN_RX_NEO4J_PASSWORD";

	private ConfigurableApplicationContext applicationContext;
	private MovieRepository movieRepository;

	private Neo4jContainer<?> neo4jContainer;

	private Driver driver;

	@Setup
	public void setup() throws IOException {
		Map<String, Object> neo4jConfig = prepareNeo4j();

		SpringApplication springApplication = new SpringApplication();
		springApplication.addPrimarySources(Collections.singletonList(Application.class));
		springApplication.setLazyInitialization(true);
		springApplication.setDefaultProperties(neo4jConfig);

		this.applicationContext = springApplication.run();
		this.movieRepository = applicationContext.getBean(MovieRepository.class);
		this.driver = applicationContext.getBean(Driver.class);

		prepareTestData();
	}

	@Benchmark
	public void simpleFindBy() {
		this.movieRepository.findByTitle("The Matrix");
	}

	@TearDown
	public void tearDown() {
		this.applicationContext.close();
		if (neo4jContainer != null) {
			this.neo4jContainer.stop();
		}
	}

	Map<String, Object> prepareNeo4j() {
		String neo4jUrl = Optional.ofNullable(System.getenv(SYS_PROPERTY_NEOJ4_URL)).orElse("");
		String neo4jPassword = Optional.ofNullable(System.getenv(SYS_PROPERTY_NEO4J_PASSWORD)).orElse("");

		if (neo4jUrl.isEmpty() || neo4jPassword.isEmpty()) {
			neo4jContainer = new Neo4jContainer<>().withAdminPassword("benchmark");
			neo4jContainer.start();
			neo4jUrl = neo4jContainer.getBoltUrl();
			neo4jPassword = neo4jContainer.getAdminPassword();
		}

		return Map.of(
			"org.neo4j.driver.authentication.password", neo4jPassword,
			"org.neo4j.driver.authentication.username", "neo4j",
			"org.neo4j.driver.uri", neo4jUrl
		);
	}

	void prepareTestData() throws IOException {
		try (BufferedReader moviesReader = new BufferedReader(
			new InputStreamReader(this.getClass().getResourceAsStream("/movies.cypher")));
			Session session = driver.session()) {
			String moviesCypher = moviesReader.lines().collect(Collectors.joining(" "));
			session.run("CREATE INDEX ON :Movie(title)");
			session.run("MATCH (n) DETACH DELETE n");
			session.run(moviesCypher);
		}
	}

	public static void main(String... args) throws RunnerException {
		Options opt = new OptionsBuilder()
			.include(Benchmarks.class.getSimpleName())
			.warmupIterations(2)
			.measurementIterations(5)
			.forks(1)
			.jvmArgsAppend("-ea")
			.build();

		new Runner(opt).run();
	}
}
