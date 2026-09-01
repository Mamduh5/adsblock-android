(function siteShieldAdObserver() {
  "use strict";
  if (typeof window.__siteShieldAdObserverDrain === "function") {
    return window.__siteShieldAdObserverDrain();
  }

  const output = [];
  const seenElements = new WeakSet();
  const seenReports = new Set();
  const maxBufferedReports = 32;
  const maxDedupKeys = 256;
  const explicitSelector = [
    "ins.adsbygoogle",
    "[data-ad-client]",
    "[data-ad-slot]",
    "[data-ad-unit-path]",
    "[data-google-query-id]",
    "[id^='google_ads_']"
  ].join(",");
  const possibleSponsoredSelector = [
    "[class*='sponsor' i]",
    "[id*='sponsor' i]",
    "[class~='advertisement' i]",
    "[aria-label='Sponsored' i]",
    "[aria-label='Advertisement' i]"
  ].join(",");

  function normalizedResource(value) {
    try {
      const parsed = new URL(value, document.baseURI);
      if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
      let path = parsed.pathname || "/";
      path = path.split("/").map(function(segment) {
        if (/^\d+$/.test(segment)) return "{numeric}";
        if (segment.length >= 16 && /^[a-f0-9_-]+$/i.test(segment)) return "{id}";
        return segment.slice(0, 80);
      }).join("/").slice(0, 240);
      return { host: parsed.hostname.toLowerCase().replace(/^\.+|\.+$/g, ""), path: path };
    } catch (error) {
      return null;
    }
  }

  function exactAttribution(element) {
    const candidates = [element].concat(Array.from(element.children || []).slice(0, 12));
    return candidates.some(function(candidate) {
      const text = (candidate.textContent || "").trim();
      if (text.length === 0 || text.length > 24 || !/^(ad|advertisement|sponsored)$/i.test(text)) return false;
      return candidate.children.length === 0 || candidate.getAttribute("aria-label") === text;
    });
  }

  function hasAdLikeIdentity(element) {
    const identity = [element.id, element.className, element.getAttribute("role"),
      element.getAttribute("aria-label")].join(" ");
    return /(^|[\s_-])(ad|ads|advert|advertisement|sponsored)([\s_-]|$)/i.test(identity);
  }

  function externalLinkPresent(element) {
    return Array.from(element.querySelectorAll("a[href]")).slice(0, 8).some(function(link) {
      const target = normalizedResource(link.href);
      return target && target.host !== location.hostname.toLowerCase();
    });
  }

  function overlayEvidence(element) {
    const style = getComputedStyle(element);
    if (style.position !== "fixed" && style.position !== "sticky") return false;
    const rect = element.getBoundingClientRect();
    const coverage = Math.max(0, rect.width) * Math.max(0, rect.height) /
      Math.max(1, innerWidth * innerHeight);
    const z = Number.parseInt(style.zIndex || "0", 10) || 0;
    return coverage >= 0.18 && z >= 100;
  }

  function loaderSources(element, explicit) {
    const sources = [];
    const declared = element.getAttribute("data-loader-src");
    if (declared) sources.push(declared);
    Array.from(element.querySelectorAll("script[src]")).slice(0, 4).forEach(function(script) {
      sources.push(script.src);
    });
    const sibling = element.previousElementSibling;
    if (sibling && sibling.tagName === "SCRIPT" && sibling.src) sources.push(sibling.src);
    if (explicit) {
      Array.from(document.scripts).slice(0, 64).forEach(function(script) {
        if (script.src && /(adsbygoogle|googlesyndication|securepubads|gpt\.js|doubleclick)/i.test(script.src)) {
          sources.push(script.src);
        }
      });
    }
    return sources;
  }

  function emit(role, resource, flags, pathScoped) {
    if (!resource || !resource.host || output.length >= maxBufferedReports) return;
    const report = ["A3", flags, role, resource.host, resource.path, String(pathScoped), "true"].join("\t");
    if (seenReports.has(report)) return;
    seenReports.add(report);
    if (seenReports.size > maxDedupKeys) {
      seenReports.delete(seenReports.values().next().value);
    }
    output.push(report);
  }

  function inspect(element) {
    if (!(element instanceof Element) || seenElements.has(element)) return;
    seenElements.add(element);
    const explicit = element.matches(explicitSelector);
    const attribution = exactAttribution(element);
    const iframe = element.querySelector("iframe[src]");
    const structural = explicit || hasAdLikeIdentity(element) || !!iframe || externalLinkPresent(element);
    const sponsored = attribution && structural && (!!iframe || hasAdLikeIdentity(element) || externalLinkPresent(element));
    if (!explicit && !sponsored) return;
    const overlay = overlayEvidence(element);
    const baseFlags = (explicit ? 1 : 0) | (sponsored ? 2 : 0) | (overlay ? 8 : 0);
    emit("STRUCTURE", normalizedResource(location.href), baseFlags, false);
    if (iframe) {
      const resource = normalizedResource(iframe.src);
      emit("IFRAME", resource, baseFlags | 4, !!resource && resource.path !== "/");
    }
    loaderSources(element, explicit).forEach(function(source) {
      emit("LOADER", normalizedResource(source), baseFlags, true);
    });
  }

  function scan(root) {
    if (!(root instanceof Element || root instanceof Document)) return;
    if (root instanceof Element && (root.matches(explicitSelector) || root.matches(possibleSponsoredSelector))) {
      inspect(root);
    }
    root.querySelectorAll(explicitSelector + "," + possibleSponsoredSelector).forEach(inspect);
  }

  window.__siteShieldAdObserverDrain = function() {
    return output.splice(0, maxBufferedReports).join("\n");
  };
  scan(document);
  const observer = new MutationObserver(function(mutations) {
    mutations.slice(0, 64).forEach(function(mutation) {
      Array.from(mutation.addedNodes).slice(0, 16).forEach(scan);
    });
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  return window.__siteShieldAdObserverDrain();
})();
