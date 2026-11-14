package com.opspulse.monitor.service;

import com.opspulse.common.exception.BadRequestException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Validates outbound HTTP/HTTPS target URLs for monitors and alert webhooks
 * to prevent SSRF attacks against loopback, link-local, and private network ranges.
 */
@Service
public class UrlSafetyValidator {

    private final boolean allowPrivateTargets;

    public UrlSafetyValidator(
            @Value("${app.monitor.allow-private-targets:false}") boolean allowPrivateTargets
    ) {
        this.allowPrivateTargets = allowPrivateTargets;
    }

    public String validateAndNormalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BadRequestException("Target URL is required");
        }

        String trimmed = rawUrl.trim();
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException ex) {
            throw new BadRequestException("Target URL is malformed");
        }

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new BadRequestException("Only http and https URLs are supported");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("Target URL must include a valid host");
        }

        if (!allowPrivateTargets) {
            validatePublicHost(host);
        }

        return trimmed;
    }

    private void validatePublicHost(String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lowerHost) || lowerHost.endsWith(".localhost")) {
            throw new BadRequestException("Target host is not permitted");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new BadRequestException("Target host could not be resolved");
        }

        for (InetAddress address : addresses) {
            if (isRestrictedAddress(address)) {
                throw new BadRequestException("Target host resolves to a restricted network address");
            }
        }
    }

    private boolean isRestrictedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        // Block IPv4 CGNAT (100.64.0.0/10)
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
        }
        // Block IPv6 Unique Local Addresses (fc00::/7)
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }
}
