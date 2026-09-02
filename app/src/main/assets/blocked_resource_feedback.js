(function applySiteShieldBlockedResourceFeedback() {
  const incoming = window.__siteShieldBlockedResourceEvidence;
  const config = window.__siteShieldBlockedFeedbackConfig || {};
  const preserveSelectors = Array.isArray(config.preserveSelectors) ? config.preserveSelectors : [];
  if (!incoming || !incoming.host || !incoming.path) return "blocked-feedback=invalid";
  const state = window.__siteShieldBlockedFeedbackState || { evidence: [], installed: false };
  window.__siteShieldBlockedFeedbackState = state;
  state.evidence.push({ host: String(incoming.host).toLowerCase(), path: String(incoming.path) });
  if (state.evidence.length > 32) state.evidence.splice(0, state.evidence.length - 32);
  const adIdentity = /(^|[\s_-])(ad|ads|advert|advertisement|banner|sponsor|promo)([\s_-]|$)/i;
  const usefulSelector = "img,picture,video,audio,canvas,input,textarea,button,nav,article,[role='navigation'],a[href]";
  let collapsed = 0;

  function matchesEvidence(element) {
    const source = element && (element.getAttribute("src") || element.getAttribute("data-src"));
    if (!source) return false;
    let url;
    try { url = new URL(source, document.baseURI); } catch (error) { return false; }
    return state.evidence.some(function(item) {
      return url.hostname.toLowerCase() === item.host &&
        (item.path === "/" || url.pathname === item.path || url.pathname.indexOf(item.path + "/") === 0);
    });
  }

  function adLike(element) {
    if (!element) return false;
    if (element.matches("[data-ad-slot],[data-ad],ins.adsbygoogle,[role='advertisement']")) return true;
    return adIdentity.test((element.id || "") + " " + (element.className || ""));
  }

  function meaningful(element) {
    if (!element) return true;
    if (element.querySelector(usefulSelector)) return true;
    return String(element.textContent || "").trim().replace(/\s+/g, " ").length >= 24;
  }

  function preserved(element) {
    return preserveSelectors.some(function(selector) {
      try { return element.matches(selector) || !!element.querySelector(selector); }
      catch (error) { return false; }
    });
  }

  function collapseBlocked(element) {
    if (!matchesEvidence(element)) return false;
    let candidate = element.parentElement;
    element.style.setProperty("display", "none", "important");
    element.setAttribute("data-site-shield-blocked-resource", "true");
    for (let depth = 0; candidate && depth < 4; depth += 1) {
      if (candidate === document.body || candidate === document.documentElement) break;
      if (preserved(candidate)) break;
      if (adLike(candidate) && !meaningful(candidate)) {
        candidate.style.setProperty("display", "none", "important");
        candidate.style.setProperty("height", "0", "important");
        candidate.style.setProperty("min-height", "0", "important");
        candidate.style.setProperty("margin", "0", "important");
        candidate.style.setProperty("padding", "0", "important");
        candidate.setAttribute("data-site-shield-collapsed-placeholder", "true");
        collapsed += 1;
        return true;
      }
      if (meaningful(candidate)) break;
      candidate = candidate.parentElement;
    }
    return true;
  }

  function inspect(root) {
    if (root && root.matches && root.matches("iframe[src],script[src]")) collapseBlocked(root);
    if (!root || !root.querySelectorAll) return;
    Array.from(root.querySelectorAll("iframe[src],script[src]")).slice(0, 256).forEach(collapseBlocked);
  }

  inspect(document);
  if (!state.installed) {
    new MutationObserver(function(mutations) {
      mutations.slice(0, 64).forEach(function(mutation) {
        Array.from(mutation.addedNodes).slice(0, 32).forEach(function(node) {
          if (node instanceof Element) inspect(node);
        });
      });
    }).observe(document.documentElement, { childList: true, subtree: true });
    state.installed = true;
  }
  return "blocked-feedback=applied; collapsed=" + collapsed;
})();
