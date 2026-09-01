(function siteShieldAdObserver() {
  "use strict";
  if (typeof window.__siteShieldAdObserverDrain === "function") {
    if (typeof window.__siteShieldAdObserverSetActive === "function") {
      window.__siteShieldAdObserverSetActive(true);
    }
    return window.__siteShieldAdObserverDrain(false);
  }

  const output = [];
  const elementSignatures = new WeakMap();
  const elementSlotIds = new WeakMap();
  const maxBufferedReports = 128;
  const maxDrainReports = 32;
  const maxSlotIds = 4096;
  let nextSlotId = 1;
  let overflowSinceDrain = 0;
  const explicitSelector = [
    "ins.adsbygoogle", "[data-ad-client]", "[data-ad-slot]", "[data-ad-unit-path]",
    "[data-google-query-id]", "[id^='google_ads_']"
  ].join(",");
  const possibleSponsoredSelector = [
    "[class*='sponsor' i]", "[id*='sponsor' i]", "[class~='advertisement' i]",
    "[aria-label='Sponsored' i]", "[aria-label='Advertisement' i]"
  ].join(",");
  const relevantAttributes = [
    "class", "id", "aria-label", "data-ad-client", "data-ad-slot", "data-ad-unit-path",
    "data-google-query-id", "data-loader-src", "src", "style"
  ];

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

  function ephemeralState(value) {
    let hash = 2166136261;
    const input = String(value || "");
    for (let index = 0; index < input.length; index += 1) {
      hash ^= input.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }

  function exactAttributionElement(element) {
    if (!(element instanceof Element)) return false;
    const text = (element.textContent || "").trim();
    if (text.length === 0 || text.length > 24 || !/^(ad|advertisement|sponsored)$/i.test(text)) return false;
    return element.children.length === 0 || (element.getAttribute("aria-label") || "").trim() === text;
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

  function hasSupportingStructure(element) {
    return element.matches(explicitSelector) || !!element.querySelector(explicitSelector + ",iframe[src],script[src]") ||
      hasAdLikeIdentity(element) || externalLinkPresent(element);
  }

  function sponsoredOwner(marker) {
    if (!exactAttributionElement(marker)) return null;
    let current = marker;
    for (let depth = 0; depth <= 4 && current; depth += 1, current = current.parentElement) {
      if (hasSupportingStructure(current)) return current;
    }
    return null;
  }

  function exactAttributionWithin(element) {
    if (exactAttributionElement(element)) return true;
    return Array.from(element.children || []).slice(0, 16).some(exactAttributionElement);
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

  function strongAdScriptIdentity(value) {
    try {
      const path = new URL(value, document.baseURI).pathname;
      return /(^|[\/_-])(ad|ads|advert|adserver|prebid|headerbid|bidder|auction)([\/_-]|\.|$)/i.test(path);
    } catch (error) {
      return false;
    }
  }

  function loaderSources(element, explicit) {
    const sources = [];
    const declared = element.getAttribute("data-loader-src");
    if (declared) sources.push(declared);
    Array.from(element.querySelectorAll("script[src]")).slice(0, 8).forEach(function(script) {
      sources.push(script.src);
    });
    [element.previousElementSibling, element.nextElementSibling].forEach(function(sibling) {
      if (sibling && sibling.tagName === "SCRIPT" && sibling.src) sources.push(sibling.src);
    });
    if (element.parentElement) {
      Array.from(element.parentElement.children).slice(0, 16).forEach(function(sibling) {
        if (sibling.tagName === "SCRIPT" && sibling.src && strongAdScriptIdentity(sibling.src)) {
          sources.push(sibling.src);
        }
      });
    }
    if (explicit) {
      Array.from(document.scripts).slice(0, 96).forEach(function(script) {
        if (script.src && (/(adsbygoogle|googlesyndication|securepubads|gpt\.js|doubleclick)/i.test(script.src) ||
            strongAdScriptIdentity(script.src))) {
          sources.push(script.src);
        }
      });
    }
    return sources.map(normalizedResource).filter(Boolean).filter(function(resource, index, all) {
      return all.findIndex(function(other) {
        return other.host === resource.host && other.path === resource.path;
      }) === index;
    });
  }

  function slotIdFor(element) {
    const existing = elementSlotIds.get(element);
    if (existing) return existing;
    if (nextSlotId > maxSlotIds) return null;
    const assigned = nextSlotId++;
    elementSlotIds.set(element, assigned);
    return assigned;
  }

  function enqueue(role, slotId, resource, flags, pathScoped) {
    if (!resource || !resource.host || !slotId) return;
    const report = ["A4", flags, role, slotId, resource.host, resource.path,
      String(pathScoped), "true"].join("\t");
    if (output.length >= maxBufferedReports) {
      overflowSinceDrain += 1;
      return;
    }
    output.push(report);
  }

  function inspect(candidate) {
    if (!(candidate instanceof Element)) return;
    const resolvedOwner = sponsoredOwner(candidate);
    const owner = resolvedOwner || candidate;
    const explicit = owner.matches(explicitSelector);
    const attribution = exactAttributionWithin(owner) || !!resolvedOwner;
    const structural = explicit || hasAdLikeIdentity(owner) || !!owner.querySelector("iframe[src],script[src]") ||
      externalLinkPresent(owner);
    const sponsored = attribution && structural;
    if (!explicit && !sponsored) return;
    const slotId = slotIdFor(owner);
    if (!slotId) return;
    const overlay = overlayEvidence(owner);
    const baseFlags = (explicit ? 1 : 0) | (sponsored ? 2 : 0) | (overlay ? 8 : 0);
    const frames = Array.from(owner.querySelectorAll("iframe[src]")).slice(0, 4)
      .map(function(frame) { return { resource: normalizedResource(frame.src), state: ephemeralState(frame.src) }; })
      .filter(function(frame) { return !!frame.resource; });
    const loaders = loaderSources(owner, explicit);
    const signature = [baseFlags]
      .concat(frames.map(function(frame) {
        return "f:" + frame.resource.host + frame.resource.path + ":" + frame.state;
      }).sort())
      .concat(loaders.map(function(resource) { return "l:" + resource.host + resource.path; }).sort())
      .join("|");
    if (elementSignatures.get(owner) === signature) return;
    elementSignatures.set(owner, signature);
    enqueue("STRUCTURE", slotId, normalizedResource(location.href), baseFlags, false);
    frames.forEach(function(frame) {
      enqueue("IFRAME", slotId, frame.resource, baseFlags | 4, frame.resource.path !== "/");
    });
    loaders.forEach(function(resource) {
      enqueue("LOADER", slotId, resource, baseFlags, true);
    });
  }

  function inspectWithAncestors(element) {
    let current = element;
    for (let depth = 0; depth <= 4 && current; depth += 1, current = current.parentElement) inspect(current);
  }

  function scan(root) {
    if (!(root instanceof Element || root instanceof Document)) return;
    if (root instanceof Element) inspectWithAncestors(root);
    Array.from(root.querySelectorAll(explicitSelector + "," + possibleSponsoredSelector)).slice(0, 256)
      .forEach(inspectWithAncestors);
    Array.from(root.querySelectorAll("span,small,label,[aria-label]")).slice(0, 512)
      .filter(exactAttributionElement).forEach(inspectWithAncestors);
  }

  window.__siteShieldAdObserverDrain = function(installed) {
    const drained = output.splice(0, maxDrainReports);
    const metadata = ["M4", installed ? "1" : "0", overflowSinceDrain, output.length].join("\t");
    overflowSinceDrain = 0;
    return [metadata].concat(drained).join("\n");
  };
  scan(document);
  const observer = new MutationObserver(function(mutations) {
    mutations.slice(0, 128).forEach(function(mutation) {
      if (mutation.type === "childList") {
        inspectWithAncestors(mutation.target instanceof Element ? mutation.target : mutation.target.parentElement);
        Array.from(mutation.addedNodes).slice(0, 32).forEach(function(node) {
          if (node instanceof Element) scan(node);
          else if (node.parentElement) inspectWithAncestors(node.parentElement);
        });
      } else if (mutation.type === "attributes") {
        inspectWithAncestors(mutation.target);
      } else if (mutation.type === "characterData" && mutation.target.parentElement) {
        inspectWithAncestors(mutation.target.parentElement);
      }
    });
  });
  let observing = false;
  window.__siteShieldAdObserverSetActive = function(active) {
    if (active && !observing) {
      observer.observe(document.documentElement, {
        childList: true, subtree: true, attributes: true,
        attributeFilter: relevantAttributes, characterData: true
      });
      observing = true;
    } else if (!active && observing) {
      observer.disconnect();
      observing = false;
    }
  };
  window.__siteShieldAdObserverSetActive(true);
  return window.__siteShieldAdObserverDrain(true);
})();
