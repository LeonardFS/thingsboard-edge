/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.msa;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.extensions.cpsuite.ClasspathSuite;
import org.junit.runner.RunWith;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

@RunWith(ClasspathSuite.class)
@ClasspathSuite.ClassnameFilters({"org.thingsboard.server.msa.EdgeClientTest"})
@Slf4j
public class ContainerTestSuite {

    public static DockerComposeContainer<?> testContainer;

    private static final String SOURCE_DIR = "./../../docker-edge/";

    @ClassRule
    public static ThingsBoardDbInstaller installTb = new ThingsBoardDbInstaller();

    @ClassRule
    public static DockerComposeContainer getTestContainer() {
        HashMap<String, String> env = new HashMap<>();
        env.put("EDGE_DOCKER_REPO", "thingsboard");
        env.put("TB_EDGE_DOCKER_NAME", "tb-edge");
        env.put("TB_EDGE_VERSION", "3.4.1EDGE");
        env.put("CLOUD_ROUTING_KEY", "280629c7-f853-ee3d-01c0-fffbb6f2ef38");
        env.put("CLOUD_ROUTING_SECRET", "g9ta4soeylw6smqkky8g");
        env.put("CLOUD_RPC_HOST", "tb-monolith");

        if (testContainer == null) {
            boolean skipTailChildContainers = Boolean.valueOf(System.getProperty("edgeBlackBoxTests.skipTailChildContainers"));
            try {
                final String targetDir = FileUtils.getTempDirectoryPath() + "/" + "ContainerTestSuite-" + UUID.randomUUID() + "/";
                log.info("targetDir {}", targetDir);
                FileUtils.copyDirectory(new File(SOURCE_DIR), new File(targetDir));

                final String httpIntegrationDir = "src/test/resources";
                FileUtils.copyDirectory(new File(httpIntegrationDir), new File(targetDir));

                class DockerComposeContainerImpl<SELF extends DockerComposeContainer<SELF>> extends DockerComposeContainer<SELF> {
                    public DockerComposeContainerImpl(File... composeFiles) {
                        super(composeFiles);
                    }

                    @Override
                    public void stop() {
                        super.stop();
                        tryDeleteDir(targetDir);
                    }
                }

                testContainer = new DockerComposeContainerImpl<>(
                        new File("./../../docker-edge/docker-compose.yml"),
                        new File("./../../docker-edge/docker-compose.postgres.yml"),
                        new File("./../../docker-edge/docker-compose.postgres.volumes.yml"))
                        .withPull(false)
                        .withLocalCompose(true)
                        .withTailChildContainers(!skipTailChildContainers)
                        .withEnv(installTb.getEnv())
                        .withEnv(env)
                        .withEnv("LOAD_BALANCER_NAME", "")
                        .withExposedService("tb-edge", 8082)
                        .withExposedService("haproxy", 80, Wait.forHttp("/swagger-ui.html").withStartupTimeout(Duration.ofSeconds(60)));
            } catch (Exception e) {
                log.error("Failed to create test container", e);
                Assert.fail("Failed to create test container");
            }
        }
        return testContainer;
    }

    private static void tryDeleteDir(String targetDir) {
        try {
            log.info("Trying to delete temp dir {}", targetDir);
            FileUtils.deleteDirectory(new File(targetDir));
        } catch (IOException e) {
            log.error("Can't delete temp directory " + targetDir, e);
        }
    }
}
