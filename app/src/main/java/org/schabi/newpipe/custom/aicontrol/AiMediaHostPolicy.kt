/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.custom.aicontrol

import java.net.URI
import org.schabi.newpipe.App
import org.schabi.newpipe.util.PeertubeHelper

/** Media resolvers on arbitrary hosts can publish LAN streaming URLs. Trust only service hosts
 * and the PeerTube instances explicitly present in this installation's settings. */
internal object AiMediaHostPolicy {
    fun youtube(host: String): Boolean = domain(host, "youtube.com") || domain(host, "youtube-nocookie.com") || host == "youtu.be"
    private fun domain(host: String, suffix: String) = host == suffix || host.endsWith(".$suffix")
    fun trustedHost(host: String, peerHosts: Set<String>): Boolean = youtube(host) ||
        domain(host, "soundcloud.com") || host == "snd.sc" || domain(host, "bandcamp.com") ||
        domain(host, "media.ccc.de") || host in peerHosts

    fun configuredPeers(): Set<String> = try {
        PeertubeHelper.getInstanceList(App.instance).mapNotNull { URI(it.url).host?.lowercase()?.trimEnd('.') }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    fun validate(url: String, peerHosts: Set<String> = configuredPeers()): String {
        val checked = AiPublicUrlPolicy.validate(url)
        val host = URI(checked).host.lowercase().trimEnd('.')
        if (!trustedHost(host, peerHosts)) throw AiFailure("UNTRUSTED_MEDIA_HOST", AiOutcome.NOT_EXECUTED)
        return checked
    }
}
