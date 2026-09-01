"use strict";

const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const observerSource = fs.readFileSync("app/src/main/assets/dom_ad_observer.js", "utf8");

class FakeElement {
  constructor(tagName, options) {
    const config = options || {};
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.parentElement = null;
    this.id = config.id || "";
    this.className = config.className || "";
    this.textContent = config.text || "";
    this.src = config.src || "";
    this.href = config.href || "";
    this.explicit = !!config.explicit;
    this.sponsoredIdentity = !!config.sponsoredIdentity;
    this.attributes = Object.assign({}, config.attributes || {});
    this.styleState = config.styleState || { position: "static", zIndex: "0" };
    this.rect = config.rect || { width: 100, height: 100 };
  }

  append(child) {
    child.parentElement = this;
    this.children.push(child);
    return child;
  }

  getAttribute(name) {
    if (name === "aria-label") return this.attributes[name] || "";
    if (name === "role") return this.attributes[name] || "";
    if (name === "data-loader-src") return this.attributes[name] || "";
    return this.attributes[name] || null;
  }

  matches(selector) {
    if (this.explicit && selector.indexOf("ins.adsbygoogle") >= 0) return true;
    if (this.sponsoredIdentity && selector.toLowerCase().indexOf("sponsor") >= 0) return true;
    return false;
  }

  descendants() {
    return this.children.reduce(function(all, child) {
      return all.concat(child, child.descendants());
    }, []);
  }

  querySelectorAll(selector) {
    const nodes = this.descendants();
    if (selector === "a[href]") return nodes.filter(function(node) { return node.tagName === "A" && node.href; });
    if (selector === "iframe[src]") return nodes.filter(function(node) { return node.tagName === "IFRAME" && node.src; });
    if (selector === "script[src]") return nodes.filter(function(node) { return node.tagName === "SCRIPT" && node.src; });
    if (selector.indexOf("iframe[src],script[src]") >= 0) {
      return nodes.filter(function(node) {
        return (node.tagName === "IFRAME" || node.tagName === "SCRIPT") && node.src;
      });
    }
    if (selector === "span,small,label,[aria-label]") {
      return nodes.filter(function(node) {
        return ["SPAN", "SMALL", "LABEL"].indexOf(node.tagName) >= 0 || !!node.getAttribute("aria-label");
      });
    }
    if (selector.indexOf("ins.adsbygoogle") >= 0) {
      return nodes.filter(function(node) { return node.explicit || node.sponsoredIdentity; });
    }
    return [];
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }

  getBoundingClientRect() {
    return this.rect;
  }
}

class FakeDocument {
  constructor(root) {
    this.documentElement = root;
    this.baseURI = "https://site-a.example/article";
  }

  querySelectorAll(selector) {
    const own = this.documentElement.matches(selector) ? [this.documentElement] : [];
    return own.concat(this.documentElement.querySelectorAll(selector));
  }

  get scripts() {
    return this.documentElement.querySelectorAll("script[src]");
  }
}

function createEnvironment(slots) {
  const root = new FakeElement("html");
  slots.forEach(function(slot) { root.append(slot); });
  let mutationCallback = null;
  let observeCount = 0;
  let disconnectCount = 0;
  class FakeMutationObserver {
    constructor(callback) { mutationCallback = callback; }
    observe() { observeCount += 1; }
    disconnect() { disconnectCount += 1; }
  }
  const document = new FakeDocument(root);
  const context = vm.createContext({
    window: {}, document: document, location: { href: document.baseURI, hostname: "site-a.example" },
    Element: FakeElement, Document: FakeDocument, MutationObserver: FakeMutationObserver,
    URL: URL, Array: Array, WeakMap: WeakMap, Math: Math, Number: Number, String: String,
    innerWidth: 1000, innerHeight: 1000,
    getComputedStyle: function(element) { return element.styleState; },
  });
  context.window = context;
  return {
    context: context,
    install: function() { return vm.runInContext(observerSource, context); },
    drain: function() { return context.window.__siteShieldAdObserverDrain(false); },
    mutate: function(mutations) { mutationCallback(mutations); },
    root: root,
    observerCounts: function() { return { observe: observeCount, disconnect: disconnectCount }; },
  };
}

function lines(result) {
  return String(result).split("\n");
}

function explicitSlot(frameUrl) {
  const slot = new FakeElement("ins", { explicit: true, className: "adsbygoogle" });
  if (frameUrl) slot.append(new FakeElement("iframe", { src: frameUrl }));
  return slot;
}

(function unchangedStateAndLateIframe() {
  const slot = explicitSlot();
  const env = createEnvironment([slot]);
  const initial = lines(env.install());
  assert(initial.some(function(line) { return line.indexOf("\tSTRUCTURE\t1\t") >= 0; }));
  assert.strictEqual(lines(env.drain()).length, 1, "unchanged state must not re-emit");

  const frame = slot.append(new FakeElement("iframe", { src: "https://frames.example/render/123" }));
  env.mutate([{ type: "childList", target: slot, addedNodes: [frame] }]);
  const populated = lines(env.drain());
  assert(populated.some(function(line) { return line.indexOf("\tIFRAME\t1\tframes.example\t/render/{numeric}") >= 0; }));
  assert.strictEqual(lines(env.drain()).length, 1, "same populated state must remain deduplicated");

  frame.src = "https://frames.example/render/456?refresh=2";
  env.mutate([{ type: "attributes", target: frame, attributeName: "src" }]);
  const refreshed = lines(env.drain());
  assert(refreshed.some(function(line) { return line.indexOf("\tIFRAME\t1\t") >= 0; }),
    "changed private page-local iframe state must re-emit normalized evidence");
})();

(function observerPauseAndResume() {
  const env = createEnvironment([explicitSlot()]);
  env.install();
  env.context.window.__siteShieldAdObserverSetActive(false);
  assert.strictEqual(env.observerCounts().disconnect, 1);
  env.install();
  assert.strictEqual(env.observerCounts().observe, 2, "reinstall drain must resume the existing observer once");
  env.install();
  assert.strictEqual(env.observerCounts().observe, 2, "repeated active drains must not duplicate observers");
})();

(function slotIdentityAndSponsoredOwner() {
  const first = explicitSlot("https://frames.example/render/1");
  const second = explicitSlot("https://frames.example/render/2");
  const card = new FakeElement("div");
  card.append(new FakeElement("span", { text: "Sponsored" }));
  card.append(new FakeElement("iframe", { src: "https://native.example/render/3" }));
  const prose = new FakeElement("p");
  prose.append(new FakeElement("span", { text: "Sponsored content policy" }));
  const env = createEnvironment([first, second, card, prose]);
  const emitted = lines(env.install());
  const frameSlots = emitted.filter(function(line) { return line.indexOf("\tIFRAME\t") >= 0; })
    .map(function(line) { return line.split("\t")[3]; });
  assert(new Set(frameSlots).size >= 3, "normalized providers must retain distinct slot identities");
  assert(emitted.some(function(line) { return line.indexOf("\tnative.example\t") >= 0; }),
    "exact sponsored child must resolve its bounded structural owner");
  assert(!emitted.some(function(line) { return line.indexOf("Sponsored content policy") >= 0; }));
})();

(function boundedFifoDrains() {
  const slots = [];
  for (let index = 1; index <= 40; index += 1) {
    slots.push(explicitSlot("https://frames.example/render/" + index));
  }
  const env = createEnvironment(slots);
  const first = lines(env.install());
  const firstMetadata = first[0].split("\t");
  assert.strictEqual(first.length - 1, 32);
  assert(Number(firstMetadata[3]) > 0, "metadata must report buffered work after a bounded drain");
  const second = lines(env.drain());
  assert.strictEqual(second.length - 1, 32);
  const third = lines(env.drain());
  assert.strictEqual(third.length - 1, 16);
  assert.strictEqual(lines(env.drain()).length, 1, "all bounded FIFO reports must eventually drain");
})();

process.stdout.write("dom_ad_observer behavioral tests passed\n");
