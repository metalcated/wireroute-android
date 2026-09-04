/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings,
	struct go_string dns_resolver_url, struct go_string dns_bootstrap_addresses,
	struct go_string dns_virtual_address);
extern void wgTurnOff(int handle);
extern int wgGetSocketV4(int handle);
extern int wgGetSocketV6(int handle);
extern char *wgGetConfig(int handle);
extern char *wgVersion();

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c,
	jstring ifname, jint tun_fd, jstring settings, jstring dns_resolver_url,
	jstring dns_bootstrap_addresses, jstring dns_virtual_address)
{
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	const char *dns_resolver_url_str = (*env)->GetStringUTFChars(env, dns_resolver_url, 0);
	size_t dns_resolver_url_len = (*env)->GetStringUTFLength(env, dns_resolver_url);
	const char *dns_bootstrap_addresses_str = (*env)->GetStringUTFChars(env, dns_bootstrap_addresses, 0);
	size_t dns_bootstrap_addresses_len = (*env)->GetStringUTFLength(env, dns_bootstrap_addresses);
	const char *dns_virtual_address_str = (*env)->GetStringUTFChars(env, dns_virtual_address, 0);
	size_t dns_virtual_address_len = (*env)->GetStringUTFLength(env, dns_virtual_address);
	int ret = wgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	}, (struct go_string){
		.str = dns_resolver_url_str,
		.n = dns_resolver_url_len
	}, (struct go_string){
		.str = dns_bootstrap_addresses_str,
		.n = dns_bootstrap_addresses_len
	}, (struct go_string){
		.str = dns_virtual_address_str,
		.n = dns_virtual_address_len
	});
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	(*env)->ReleaseStringUTFChars(env, dns_resolver_url, dns_resolver_url_str);
	(*env)->ReleaseStringUTFChars(env, dns_bootstrap_addresses, dns_bootstrap_addresses_str);
	(*env)->ReleaseStringUTFChars(env, dns_virtual_address, dns_virtual_address_str);
	return ret;
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	wgTurnOff(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV4(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV4(handle);
}

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetSocketV6(JNIEnv *env, jclass c, jint handle)
{
	return wgGetSocketV6(handle);
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = wgGetConfig(handle);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = wgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}
