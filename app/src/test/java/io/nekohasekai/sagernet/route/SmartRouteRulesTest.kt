package io.nekohasekai.sagernet.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartRouteRulesTest {

    @Test
    fun classifyAppsSeparatesDomesticForeignAndExcludesBrowsers() {
        val sampleInstalled = setOf(
            "com.tencent.mm",
            "com.eg.android.AlipayGphone",
            "com.icbc.im",
            "cn.gov.tax.its",
            "org.telegram.messenger",
            "com.google.android.youtube",
            "com.openai.chatgpt",
            "com.zhiliaoapp.musically",
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx"
        )

        val (domesticCn, foreignCn) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = setOf("com.android.chrome", "org.telegram.messenger", "android")
        )

        // 国内应用应归入直连
        assertTrue(domesticCn.contains("com.tencent.mm"))
        assertTrue(domesticCn.contains("com.eg.android.AlipayGphone"))
        assertTrue(domesticCn.contains("com.icbc.im"))
        assertTrue(domesticCn.contains("cn.gov.tax.its"))

        // 国外专属应用（含 TikTok 国际版）应归入代理，绝不被字节前缀误判为国内
        assertTrue(foreignCn.contains("org.telegram.messenger"))
        assertTrue(foreignCn.contains("com.google.android.youtube"))
        assertTrue(foreignCn.contains("com.openai.chatgpt"))
        assertTrue(foreignCn.contains("com.zhiliaoapp.musically"))
        assertFalse(domesticCn.contains("com.zhiliaoapp.musically"))

        // 浏览器与系统核心不得被锁死在应用层，确保其内部网页流量交由下层域名/IP 动态分流
        assertFalse(domesticCn.contains("com.android.chrome"))
        assertFalse(foreignCn.contains("com.android.chrome"))
        assertFalse(domesticCn.contains("org.mozilla.firefox"))
        assertFalse(foreignCn.contains("org.mozilla.firefox"))
        assertFalse(domesticCn.contains("com.microsoft.emmx"))
        assertFalse(foreignCn.contains("com.microsoft.emmx"))
        assertFalse(foreignCn.contains("android"))
    }

    @Test
    fun buildSmartRulesAdaptsToSelectedCountry() {
        val cnRules = SmartRouteRules.buildSmartRules("cn")
        val googlePlayRule = cnRules.first { it.name == "Smart-GooglePlay" }
        assertTrue(googlePlayRule.domains.contains("domain:google.cn"))
        assertTrue(googlePlayRule.domains.contains("domain:gvt5.com"))
        assertTrue(googlePlayRule.domains.contains("domain:ggpht.com"))
        assertTrue(cnRules.any { it.outbound == -1L && it.domains.contains("geosite:cn") && it.domains.contains("domain:cn") })
        assertTrue(cnRules.any { it.outbound == -1L && it.ip.contains("geoip:cn") })

        val ruRules = SmartRouteRules.buildSmartRules("ru")
        assertTrue(ruRules.any { it.outbound == -1L && it.domains.contains("geosite:ru") && it.domains.contains("domain:ru") })
        assertTrue(ruRules.any { it.outbound == -1L && it.ip.contains("geoip:ru") })

        val irRules = SmartRouteRules.buildSmartRules("ir")
        assertTrue(irRules.any { it.outbound == -1L && it.domains.contains("geosite:ir") && it.domains.contains("domain:ir") })
        assertTrue(irRules.any { it.outbound == -1L && it.ip.contains("geoip:ir") })
    }

    @Test
    fun explicitUserRulesTakePriorityOverGeneratedSmartFallbacks() {
        val blockRule = io.nekohasekai.sagernet.database.RuleEntity(
            name = "user-block",
            domains = "domain:malware.example",
            outbound = -2L,
            enabled = true
        )
        val customRule = io.nekohasekai.sagernet.database.RuleEntity(
            name = "user-custom",
            domains = "domain:example.com",
            outbound = 0L,
            enabled = true
        )

        val merged = SmartRouteRules.mergeUserRules(
            listOf(customRule, blockRule),
            "cn"
        )

        assertTrue(merged.first() === blockRule)
        assertTrue(merged[1] === customRule)
        assertTrue(merged[2].name == "Smart-GooglePlay")
    }

    @Test
    fun classifyAppsDoesNotTreatCountryCodePackageNamespacesAsDomesticProof() {
        val sampleInstalled = setOf(
            "cn.unrelated.foreignapp",
            "com.cn.unrelated.foreignapp",
            "ru.unrelated.foreignapp",
            "ir.unrelated.foreignapp",
            "tr.unrelated.foreignapp",
            "id.unrelated.foreignapp",
            "in.unrelated.foreignapp",
            "vn.unrelated.foreignapp",
            "br.unrelated.foreignapp",
            "com.tencent.mm",
            "cn.gov.tax.its"
        )

        val (domesticCn, _) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet()
        )
        assertFalse(domesticCn.contains("cn.unrelated.foreignapp"))
        assertFalse(domesticCn.contains("com.cn.unrelated.foreignapp"))
        assertTrue(domesticCn.contains("com.tencent.mm"))
        assertTrue(domesticCn.contains("cn.gov.tax.its"))

        val (domesticRu, _) = SmartRouteRules.classifyApps(
            country = "ru",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet()
        )
        assertFalse(domesticRu.contains("ru.unrelated.foreignapp"))

        for (country in listOf("ir", "tr", "id", "in", "vn", "br")) {
            val packageName = "$country.unrelated.foreignapp"
            val (domestic, _) = SmartRouteRules.classifyApps(
                country = country,
                installedPackages = setOf(packageName),
                bundledForeign = emptySet()
            )
            assertFalse("$country package namespace must not imply domestic origin", domestic.contains(packageName))
        }
    }

    @Test
    fun classifyAppsRespectsPerAppProxySelection() {
        val sampleInstalled = setOf(
            "tv.danmaku.bili",
            "com.tencent.mm",
            "org.telegram.messenger"
        )

        // 当分应用代理开启且为「仅代理选中应用 (!bypassMode)」时，手动勾选的国内应用 tv.danmaku.bili 应走代理而非强制直连
        val (domesticProxyMode, foreignProxyMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = false,
            individualPackages = setOf("tv.danmaku.bili")
        )
        assertFalse(domesticProxyMode.contains("tv.danmaku.bili"))
        assertTrue(foreignProxyMode.contains("tv.danmaku.bili"))

        // 当分应用代理开启且为「绕过选中应用 (bypassMode)」时，手动勾选绕过的应用应从代理名单移除并归入直连
        val (domesticBypassMode, foreignBypassMode) = SmartRouteRules.classifyApps(
            country = "cn",
            installedPackages = sampleInstalled,
            bundledForeign = emptySet(),
            proxyAppsEnabled = true,
            bypassMode = true,
            individualPackages = setOf("org.telegram.messenger")
        )
        assertFalse(foreignBypassMode.contains("org.telegram.messenger"))
        assertTrue(domesticBypassMode.contains("org.telegram.messenger"))
    }
}
