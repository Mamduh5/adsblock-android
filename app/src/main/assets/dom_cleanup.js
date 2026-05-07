(function siteShieldDomCleanup() {
  if (window.__siteShieldInstalled) {
    return 0;
  }
  window.__siteShieldInstalled = true;

  const baitText = /^(continue|allow|download|skip|start|open|close|claim|accept|watch now|play now)$/i;
  const suspiciousUrl = /(adserver|doubleclick|googlesyndication|googleadservices|taboola|outbrain|popads|propellerads|onclickads|adsterra|redirect|popunder|interstitial|campaign|promo)/i;
  const suspiciousClass = /(overlay|modal|popup|popunder|interstitial|ad-|ads-|advert|banner|sponsor|redirect|clicktrap|fake-close|subscribe|campaign|promo)/i;
  let removed = 0;

  function parseZIndex(value) {
    const parsed = Number.parseInt(value || "0", 10);
    return Number.isFinite(parsed) ? parsed : 0;
  }

  function viewportCoverage(rect) {
    const viewportArea = Math.max(1, window.innerWidth * window.innerHeight);
    const elementArea = Math.max(0, rect.width) * Math.max(0, rect.height);
    return elementArea / viewportArea;
  }

  function neutralize(element, reason) {
    if (!element || element === document.documentElement || element === document.body) {
      return;
    }
    element.setAttribute("data-site-shield-removed", reason);
    element.style.setProperty("display", "none", "important");
    element.style.setProperty("pointer-events", "none", "important");
    element.remove();
    removed += 1;
    console.info("[SiteShield] removed " + reason);
  }

  function isSuspiciousFrame(element) {
    const src = element.getAttribute("src") || "";
    const idClass = `${element.id || ""} ${element.className || ""}`;
    return suspiciousUrl.test(src) || suspiciousClass.test(idClass);
  }

  function isSuspiciousOverlay(element, style, rect) {
    const position = style.position;
    const zIndex = parseZIndex(style.zIndex);
    const highLayer = zIndex >= 999 || style.pointerEvents === "auto";
    const coversMuch = viewportCoverage(rect) >= 0.28;
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

  function inspectElement(element) {
    if (!(element instanceof HTMLElement)) {
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
    }
  }

  function scan(root) {
    const scope = root && root.querySelectorAll ? root : document;
    scope.querySelectorAll("iframe, [class], [id], a, button, [role='button']").forEach(inspectElement);
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
      console.info("[SiteShield] blocked suspicious click target");
    }
  }

  scan(document);

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

  return removed;
})();
