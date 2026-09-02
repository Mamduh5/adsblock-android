"use strict";

const assert = require("assert");
const fs = require("fs");
const vm = require("vm");

const intentSource = fs.readFileSync("app/src/main/assets/navigation_intent_observer.js", "utf8");
const feedbackSource = fs.readFileSync("app/src/main/assets/blocked_resource_feedback.js", "utf8");

{
  let clickHandler;
  const messages = [];
  class Element {}
  const link = Object.assign(new Element(), {
    href: "https://target.example/chapter/12?secret=TRACK",
    closest: () => link,
    getAttribute: (name) => name === "target" ? "_blank" : null,
  });
  const token = "a".repeat(32);
  const context = { window: { __siteShieldIntentGeneration: 7, __siteShieldIntentChannelToken: token }, document: {
    baseURI: "https://reader.example/", addEventListener: (_, fn) => { clickHandler = fn; }, removeEventListener: () => {},
  }, Element, URL, console: { info: (value) => messages.push(value) }, encodeURIComponent };
  vm.createContext(context);
  assert.strictEqual(vm.runInContext(intentSource, context), "intent=installed");
  clickHandler({ target: link });
  assert.strictEqual(messages.length, 1);
  assert(messages[0].includes("N1|7|" + token + "|target.example|%2Fchapter%2F%7Bnumeric%7D|1"));
  assert(!messages[0].includes("secret"));
  assert(!messages[0].includes("TRACK"));
  clickHandler({ target: Object.assign(new Element(), { closest: () => null }) });
  assert.strictEqual(messages.length, 1);
}

function feedbackFixture(options) {
  const opts = Object.assign({ host: "ads.example", path: "/frame", adLike: true, useful: false }, options);
  class Element {
    constructor(tag, source) {
      this.tag = tag; this.source = source; this.parentElement = null; this.children = [];
      this.id = ""; this.className = ""; this.textContent = ""; this.attributes = {};
      this.style = { values: {}, setProperty: (key, value) => { this.style.values[key] = value; } };
    }
    getAttribute(name) { return name === "src" ? this.source : this.attributes[name] || null; }
    setAttribute(name, value) { this.attributes[name] = value; }
    matches(selector) {
      if (selector === "iframe[src],script[src]") return this.tag === "iframe" || this.tag === "script";
      if (selector.includes("data-ad-slot")) return opts.adLike && this.tag === "wrapper";
      return false;
    }
    querySelector(selector) { return opts.useful && selector.includes("img") ? {} : null; }
    querySelectorAll(selector) {
      const all = [];
      function visit(node) { node.children.forEach((child) => { if (child.matches(selector)) all.push(child); visit(child); }); }
      visit(this); return all;
    }
  }
  const html = new Element("html");
  const body = new Element("body"); body.parentElement = html; html.children.push(body);
  const wrapper = new Element("wrapper"); wrapper.parentElement = body; body.children.push(wrapper);
  const frame = new Element("iframe", opts.source || "https://ads.example/frame?id=secret");
  frame.parentElement = wrapper; wrapper.children.push(frame);
  let observerCallback;
  class MutationObserver { constructor(callback) { observerCallback = callback; } observe() {} }
  const document = { baseURI: "https://reader.example/", body, documentElement: html,
    querySelectorAll: (selector) => html.querySelectorAll(selector) };
  const context = { window: { __siteShieldBlockedResourceEvidence: { host: opts.host, path: opts.path, kind: "OTHER" } },
    document, Element, MutationObserver, URL, Array };
  vm.createContext(context);
  const result = vm.runInContext(feedbackSource, context);
  return { result, wrapper, frame, observerCallback, Element };
}

{
  const fixture = feedbackFixture({});
  assert.strictEqual(fixture.wrapper.attributes["data-site-shield-collapsed-placeholder"], "true");
  assert.strictEqual(fixture.wrapper.style.values.height, "0");
  assert.strictEqual(fixture.frame.attributes["data-site-shield-blocked-resource"], "true");
}
assert.strictEqual(feedbackFixture({ adLike: false }).wrapper.attributes["data-site-shield-collapsed-placeholder"], undefined);
assert.strictEqual(feedbackFixture({ useful: true }).wrapper.attributes["data-site-shield-collapsed-placeholder"], undefined);
assert.strictEqual(feedbackFixture({ host: "other.example" }).frame.attributes["data-site-shield-blocked-resource"], undefined);
assert.strictEqual(feedbackFixture({ path: "/different" }).frame.attributes["data-site-shield-blocked-resource"], undefined);

{
  const fixture = feedbackFixture({ source: "" });
  const dynamic = new fixture.Element("iframe", "https://ads.example/frame/dynamic");
  dynamic.parentElement = fixture.wrapper;
  fixture.wrapper.children.push(dynamic);
  fixture.observerCallback([{ addedNodes: [dynamic] }]);
  assert.strictEqual(dynamic.attributes["data-site-shield-blocked-resource"], "true");
}

process.stdout.write("adaptive v3.2 asset behavioral tests passed\n");
