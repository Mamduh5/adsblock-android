(function installSiteShieldNavigationIntentObserver() {
  const generation = Number(window.__siteShieldIntentGeneration);
  const channelToken = String(window.__siteShieldIntentChannelToken || "");
  delete window.__siteShieldIntentGeneration;
  delete window.__siteShieldIntentChannelToken;
  if (!Number.isSafeInteger(generation) || generation < 0 || !/^[a-f0-9]{32}$/.test(channelToken)) {
    return "intent=invalid-channel";
  }
  if (window.__siteShieldIntentClickHandler) {
    document.removeEventListener("click", window.__siteShieldIntentClickHandler, true);
  }

  function normalizedPath(pathname) {
    return String(pathname || "/").split("/").map(function(segment) {
      if (/^\d+$/.test(segment)) return "{numeric}";
      if (segment.length >= 16 && /^[a-f0-9_-]+$/i.test(segment)) return "{id}";
      return segment.slice(0, 80);
    }).join("/").slice(0, 240) || "/";
  }

  const clickHandler = function(event) {
    const target = event.target instanceof Element ? event.target : null;
    const link = target ? target.closest("a[href],area[href]") : null;
    if (!link) return;
    let destination;
    try { destination = new URL(link.href, document.baseURI); } catch (error) { return; }
    if (destination.protocol !== "http:" && destination.protocol !== "https:") return;
    const blank = String(link.getAttribute("target") || "").toLowerCase() === "_blank" ? "1" : "0";
    console.info("[SiteShieldIntent] N1|" + generation + "|" + channelToken + "|" +
      destination.hostname.toLowerCase() + "|" + encodeURIComponent(normalizedPath(destination.pathname)) + "|" + blank);
  };
  document.addEventListener("click", clickHandler, true);
  window.__siteShieldIntentClickHandler = clickHandler;
  window.__siteShieldIntentObserverInstalled = true;
  return "intent=installed";
})();
