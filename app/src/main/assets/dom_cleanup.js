(function siteShieldDomCleanup() {
  const config = window.__siteShieldDomConfig || {};
  const suspiciousSelectors = Array.isArray(config.suspiciousSelectors) ? config.suspiciousSelectors : [];
  const preserveSelectors = Array.isArray(config.preserveSelectors) ? config.preserveSelectors : [];
  const highZIndexThreshold = Number.isFinite(config.highZIndexThreshold) ? config.highZIndexThreshold : 999;
  const overlayViewportCoverageThreshold = Number.isFinite(config.overlayViewportCoverageThreshold)
    ? config.overlayViewportCoverageThreshold
    : 0.28;
  const baitText = tokenRegex(config.baitTextTokens, true);
  const junkText = tokenRegex(config.junkTextTokens, false);
  const suspiciousUrl = tokenRegex(config.suspiciousUrlTokens, false);
  const suspiciousClass = tokenRegex(config.suspiciousClassTokens, false);
  let removed = 0;
  const removedFamilies = {};

  function tokenRegex(tokens, exact) {
    const safeTokens = Array.isArray(tokens) ? tokens.filter(Boolean) : [];
    if (safeTokens.length === 0) {
      return /$a/;
    }
    const source = safeTokens.map(escapeRegex).join("|");
    return exact ? new RegExp("^(" + source + ")$", "i") : new RegExp("(" + source + ")", "i");
  }

  function escapeRegex(value) {
    return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  }

  function parseZIndex(value) {
    const parsed = Number.parseInt(value || "0", 10);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function viewportCoverage(rect) {
    const viewportArea = Math.max(1, window.innerWidth * window.innerHeight);
    const elementArea = Math.max(0, rect.width) * Math.max(0, rect.height);
    return elementArea / viewportArea;
  }

  function isPreserved(element) {
    if (!element || preserveSelectors.length === 0) {
      return false;
    }
    for (const selector of preserveSelectors) {
      try {
        if (element.matches(selector) || element.querySelector(selector)) {
          return true;
        }
      } catch (error) {
        console.info("[SiteShield] ignored invalid preserve selector");
      }
    }
    return false;
  }

  function neutralize(element, reason) {
    if (!element || element === document.documentElement || element === document.body) {
      return;
    }
    if (isPreserved(element)) {
      return;
    }
    element.setAttribute("data-site-shield-removed", reason);
    element.style.setProperty("display", "none", "important");
    element.style.setProperty("pointer-events", "none", "important");
    element.remove();
    removed += 1;
    removedFamilies[reason] = (removedFamilies[reason] || 0) + 1;
    console.info("[SiteShield] dom family=" + reason + " removed=1");
  }

  function isSuspiciousFrame(element) {
    const src = element.getAttribute("src") || "";
    const idClass = `${element.id || ""} ${element.className || ""}`;
    return suspiciousUrl.test(src) || suspiciousClass.test(idClass);
  }

  function isSuspiciousOverlay(element, style, rect) {
    const position = style.position;
    const zIndex = parseZIndex(style.zIndex);
    const highLayer = zIndex >= highZIndexThreshold || style.pointerEvents === "auto";
    const coversMuch = viewportCoverage(rect) >= overlayViewportCoverageThreshold;
    const fixedOrSticky = position === "fixed" || position === "sticky";
    const idClass = `${element.id || ""} ${element.className || ""}`;
    return fixedOrSticky && highLayer && (coversMuch || suspiciousClass.test(idClass));
  }

  function isDeceptiveBait(element, style) {
    const text = (element.textContent || "").trim().replace(/\s+/g, " ").slice(0, 80);
    if (!baitText.test(text)) {
      return false;
    }
    const role = element.getAttribute("role") || "";
    const tag = element.tagName.toLowerCase();
    const idClass = `${element.id || ""} ${element.className || ""}`;
    const href = element.getAttribute("href") || "";
    const looksClickable = tag === "a" || tag === "button" || role === "button" || style.cursor === "pointer";
    return looksClickable && (suspiciousClass.test(idClass) || suspiciousUrl.test(href));
  }

  function isKnownJunkText(element) {
    const text = (element.textContent || "").trim().replace(/\s+/g, " ").slice(0, 80);
    if (!junkText.test(text)) {
      return false;
    }
    const idClass = `${element.id || ""} ${element.className || ""}`;
    const parentClass = element.parentElement ? `${element.parentElement.id || ""} ${element.parentElement.className || ""}` : "";
    return suspiciousClass.test(idClass) || suspiciousClass.test(parentClass);
  }

  function removeEmptySuspiciousWrapper(element) {
    const idClass = `${element.id || ""} ${element.className || ""}`;
    if (!suspiciousClass.test(idClass) || isPreserved(element)) {
      return;
    }
    const hasUsefulContent = element.querySelector("img, picture, source, select, input, textarea, video, canvas, a[href*='chapter']");
    if (!hasUsefulContent && (element.textContent || "").trim().length < 24) {
      neutralize(element, "empty-suspicious-wrapper");
    }
  }

  function inspectElement(element) {
    if (!(element instanceof HTMLElement)) {
      return;
    }
    if (isPreserved(element)) {
      return;
    }

    if (element.matches("iframe") && isSuspiciousFrame(element)) {
      neutralize(element, "suspicious-frame");
      return;
    }

    const style = window.getComputedStyle(element);
    const rect = element.getBoundingClientRect();

    if (isSuspiciousOverlay(element, style, rect)) {
      neutralize(element, "suspicious-overlay");
      return;
    }

    if (isDeceptiveBait(element, style)) {
      neutralize(element, "deceptive-bait");
      return;
    }

    if (isKnownJunkText(element)) {
      neutralize(element, "known-junk-text");
      return;
    }

    removeEmptySuspiciousWrapper(element);
  }

  function scan(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll("iframe, [class], [id], a, button, [role='button']").forEach(inspectElement);
    for (const selector of suspiciousSelectors) {
      try {
        scope.querySelectorAll(selector).forEach(function(element) {
          neutralize(element, "profile-selector:" + selector);
        });
      } catch (error) {
        console.info("[SiteShield] ignored invalid selector");
      }
    }
  }

  function blockClickTrap(event) {
    const target = event.target instanceof HTMLElement ? event.target : null;
    if (!target) {
      return;
    }

    const link = target.closest("a[href]");
    const href = link ? link.getAttribute("href") || "" : "";
    const idClass = `${target.id || ""} ${target.className || ""}`;
    if (suspiciousUrl.test(href) || suspiciousClass.test(idClass)) {
      event.preventDefault();
      event.stopPropagation();
      removed += 1;
      removedFamilies["suspicious-click-target"] = (removedFamilies["suspicious-click-target"] || 0) + 1;
      console.info("[SiteShield] dom family=suspicious-click-target blocked=1");
    }
  }

  scan(document);

  if (!window.__siteShieldInstalled) {
    const observer = new MutationObserver(function(mutations) {
      for (const mutation of mutations) {
        mutation.addedNodes.forEach(function(node) {
          if (node instanceof HTMLElement) {
            inspectElement(node);
            scan(node);
          }
        });
      }
    });

    observer.observe(document.documentElement, {
      childList: true,
      subtree: true
    });

    document.addEventListener("click", blockClickTrap, true);
    window.__siteShieldInstalled = true;
  }

  const familySummary = Object.keys(removedFamilies)
    .sort()
    .map(function(key) {
      return key + ":" + removedFamilies[key];
    })
    .join(",");

  return "removed=" + removed + "; families=" + (familySummary || "none");
})();
