/*
 * Copyright 2016-2019 the original author or authors.
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
package org.springframework.vault.core;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.vault.support.Policy;
import org.springframework.vault.support.VaultMount;
import org.springframework.vault.support.VaultResponse;
import org.springframework.vault.support.VaultUnsealStatus;
import org.springframework.vault.support.Policy.Rule;
import org.springframework.vault.util.IntegrationTestSupport;
import org.springframework.vault.util.VaultRule;
import org.springframework.vault.util.Version;

import static org.assertj.core.api.Assertions.*;
import static org.junit.Assume.assumeTrue;
import static org.springframework.vault.support.Policy.BuiltinCapabilities.READ;
import static org.springframework.vault.support.Policy.BuiltinCapabilities.UPDATE;

/**
 * Integration tests for {@link VaultSysTemplate} through {@link VaultSysOperations}.
 *
 * @author Mark Paluch
 */
@RunWith(SpringRunner.class)
@ContextConfiguration(classes = VaultIntegrationTestConfiguration.class)
public class VaultSysTemplateIntegrationTests extends IntegrationTestSupport {

	@Autowired
	private VaultOperations vaultOperations;

	private Version vaultVersion;

	private VaultSysOperations adminOperations;

	@Before
	public void before() {

		vaultVersion = prepare().getVersion();

		adminOperations = vaultOperations.opsForSys();
	}

	@Test
	public void getMountsShouldContainSecretBackend() {

		Map<String, VaultMount> mounts = adminOperations.getMounts();

		assertThat(mounts).containsKey("secret/");

		VaultMount secret = mounts.get("secret/");
		assertThat(Arrays.asList("kv", "generic")).contains(secret.getType());
	}

	@Test
	public void mountShouldMountGenericSecret() {

		if (adminOperations.getMounts().containsKey("other/")) {
			adminOperations.unmount("other");
		}

		VaultMount mount = VaultMount.builder().type("generic")
				.config(Collections.singletonMap("default_lease_ttl", "1h"))
				.description("hello, world").build();

		adminOperations.mount("other", mount);

		Map<String, VaultMount> mounts = adminOperations.getMounts();

		assertThat(mounts).containsKey("other/");

		VaultMount secret = mounts.get("other/");
		assertThat(secret.getDescription()).isEqualTo(mount.getDescription());
		assertThat(secret.getConfig()).containsEntry("default_lease_ttl", 3600);
		assertThat(Arrays.asList("kv", "generic")).contains(secret.getType());
	}

	@Test
	public void mountShouldMountKv1Secret() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(
			VaultRule.VERSIONING_INTRODUCED_WITH));

		if (adminOperations.getMounts().containsKey("kVv1/")) {
			adminOperations.unmount("kVv1");
		}

		VaultMount mount = VaultMount.builder().type("kv")
			.config(Collections.singletonMap("default_lease_ttl",  "1h"))
			.description("hello, world").build();

		adminOperations.mount("kVv1", mount);

		Map<String, VaultMount> mounts = adminOperations.getMounts();

		assertThat(mounts).containsKey("kVv1/");

		VaultMount kVv1 = mounts.get("kVv1/");
		assertThat(kVv1.getDescription()).isEqualTo(mount.getDescription());
		assertThat(kVv1.getConfig()).containsEntry("default_lease_ttl", 3600);
		assertThat(kVv1.getType()).isEqualTo("kv");

		// a versioned write (kv put) will fail for a kv (default version: 1, not versioned) store
		// make sure regular VaultTemplate.write/read operations work

		vaultOperations.write("secret/mykey", Collections.singletonMap("hello", "world"));

		VaultResponse read = vaultOperations.read("secret/mykey");
		assertThat(read).isNotNull();
		assertThat(read.getData()).containsEntry("hello", "world");
	}

	@Test
	public void mountShouldMountKv2Secret() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(
			VaultRule.VERSIONING_INTRODUCED_WITH));

		if (adminOperations.getMounts().containsKey("kVv2/")) {
			adminOperations.unmount("kVv2");
		}

		VaultMount mount = VaultMount.builder().type("kv")
			.config(Collections.singletonMap("default_lease_ttl", "1h"))
			.options(Collections.singletonMap("version", "2"))
			.description("hello, world").build();

		adminOperations.mount("kVv2", mount);

		Map<String, VaultMount> mounts = adminOperations.getMounts();

		assertThat(mounts).containsKey("kVv2/");

		VaultMount kVv2 = mounts.get("kVv2/");
		assertThat(kVv2.getDescription()).isEqualTo(mount.getDescription());
		assertThat(kVv2.getConfig()).containsEntry("default_lease_ttl", 3600);
		assertThat(kVv2.getType()).isEqualTo("kv");
		assertThat(kVv2.getOptions()).containsEntry("version", "2");

		VaultVersionedKeyValueOperations versionedOperations =
			vaultOperations.opsForVersionedKeyValue("kVv2");

		versionedOperations.put("secret/mykey", Collections.singletonMap("key", "value"));
		assertThat(versionedOperations.get("secret/mykey").getData())
			.containsEntry("key", "value");
	}

	@Test
	public void getAuthMountsShouldContainSecretBackend() {

		Map<String, VaultMount> mounts = adminOperations.getAuthMounts();

		assertThat(mounts).containsKey("token/");

		VaultMount secret = mounts.get("token/");
		assertThat(secret.getDescription()).isEqualTo("token based credentials");
		assertThat(secret.getType()).isEqualTo("token");
	}

	@Test
	public void authMountShouldMountGenericSecret() {

		if (adminOperations.getAuthMounts().containsKey("other/")) {
			adminOperations.authUnmount("other");
		}

		VaultMount mount = VaultMount.builder().type("userpass")
				.description("hello, world").build();

		adminOperations.authMount("other", mount);

		Map<String, VaultMount> mounts = adminOperations.getAuthMounts();

		assertThat(mounts).containsKey("other/");

		VaultMount secret = mounts.get("other/");
		assertThat(secret.getDescription()).isEqualTo(mount.getDescription());
		assertThat(secret.getType()).isEqualTo("userpass");
	}

	@Test
	public void shouldEnumeratePolicyNames() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.1")));

		List<String> policyNames = adminOperations.getPolicyNames();

		assertThat(policyNames).contains("root", "default");
	}

	@Test
	public void shouldReadRootPolicy() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.1")));

		Policy root = adminOperations.getPolicy("root");

		assertThat(root).isEqualTo(Policy.empty());
	}

	@Test
	public void shouldReadAbsentRootPolicy() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.1")));

		Policy root = adminOperations.getPolicy("absent-policy");

		assertThat(root).isNull();
	}

	@Test(expected = UnsupportedOperationException.class)
	public void shouldReadDefaultPolicy() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.1")));

		adminOperations.getPolicy("default");
	}

	@Test
	public void shouldCreatePolicy() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.7.0")));

		Rule rule = Rule.builder().path("foo").capabilities(READ, UPDATE)
				.minWrappingTtl(Duration.ofSeconds(100))
				.maxWrappingTtl(Duration.ofHours(2)).build();

		adminOperations.createOrUpdatePolicy("foo", Policy.of(rule));

		assertThat(adminOperations.getPolicyNames()).contains("foo");

		Policy loaded = adminOperations.getPolicy("foo");
		assertThat(loaded.getRules()).contains(rule);
	}

	@Test
	public void shouldDeletePolicy() {

		assumeTrue(vaultVersion.isGreaterThanOrEqualTo(Version.parse("0.6.0")));

		Rule rule = Rule.builder().path("foo").capabilities(READ).build();

		adminOperations.createOrUpdatePolicy("foo", Policy.of(rule));

		adminOperations.deletePolicy("foo");

		assertThat(adminOperations.getPolicyNames()).doesNotContain("foo");
	}

	@Test
	public void isInitializedShouldReturnTrue() {
		assertThat(adminOperations.isInitialized()).isTrue();
	}

	@Test
	public void getUnsealStatusShouldReturnStatus() {

		VaultUnsealStatus unsealStatus = adminOperations.getUnsealStatus();

		assertThat(unsealStatus.isSealed()).isFalse();
		assertThat(unsealStatus.getProgress()).isEqualTo(0);
	}
}
