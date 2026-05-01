// ==UserScript==
// @name         SBG Vanilla+
// @namespace    https://github.com/wrager/sbg-vanilla-plus
// @version      0.10.1
// @author       wrager
// @description  UI/UX enhancements for SBG (SBG v0.6.0 / 0.6.1)
// @license      MIT
// @homepage     https://github.com/wrager/sbg-vanilla-plus
// @homepageURL  https://github.com/wrager/sbg-vanilla-plus
// @source       https://github.com/wrager/sbg-vanilla-plus.git
// @downloadURL  https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js
// @updateURL    https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js
// @match        https://sbg-game.ru/app/*
// @match        https://beta.sbg-game.ru/app/*
// @grant        none
// @run-at       document-start
// ==/UserScript==

(function () {
  'use strict';

  const STORAGE_KEY$4 = "svp_disabled";
  function isDisabled() {
    const hash = location.hash;
    const match = /[#&]svp-disabled=([01])/.exec(hash);
    if (match) {
      if (match[1] === "1") {
        sessionStorage.setItem(STORAGE_KEY$4, "1");
      } else {
        sessionStorage.removeItem(STORAGE_KEY$4);
      }
    }
    return sessionStorage.getItem(STORAGE_KEY$4) === "1";
  }
  const GAME_SCRIPT_PATTERN = /^script@/;
  const PATCHES = [
    // Экспозиция showInfo на window для прямого открытия попапа (refs/game/script.js:1687)
    ["class Bitfield", "window.showInfo = showInfo; class Bitfield"]
  ];
  function isGameScript(node) {
    return node instanceof HTMLScriptElement && node.type === "module" && GAME_SCRIPT_PATTERN.test(node.getAttribute("src") ?? "");
  }
  function applyPatches(source) {
    let result = source;
    let appliedCount = 0;
    for (const [search, replacement] of PATCHES) {
      if (result.includes(search)) {
        result = result.replace(search, replacement);
        appliedCount++;
      }
    }
    return { result, appliedCount };
  }
  const EXPECTED_PATCHES_COUNT = PATCHES.length;
  function installGameScriptPatcher() {
    const originalAppend = Element.prototype.append;
    Element.prototype.append = function(...args) {
      for (const argument of args) {
        if (isGameScript(argument)) {
          Element.prototype.append = originalAppend;
          void patchAndInject(argument.src, originalAppend);
          return;
        }
      }
      originalAppend.apply(this, args);
    };
  }
  async function patchAndInject(originalSrc, appendFunction) {
    try {
      const response = await fetch(originalSrc);
      const source = await response.text();
      const { result, appliedCount } = applyPatches(source);
      if (appliedCount !== EXPECTED_PATCHES_COUNT) {
        console.warn(
          `[SVP] Применено ${appliedCount}/${EXPECTED_PATCHES_COUNT} патчей скрипта игры. Игра обновилась?`
        );
      }
      const script = document.createElement("script");
      script.type = "module";
      script.textContent = result;
      appendFunction.call(document.head, script);
    } catch (error) {
      console.error("[SVP] Патчинг скрипта не удался, загружаем оригинал", error);
      const script = document.createElement("script");
      script.type = "module";
      script.src = originalSrc;
      appendFunction.call(document.head, script);
    }
  }
  const SBG_COMPATIBLE_VERSIONS = ["0.6.0", "0.6.1"];
  const VERSION_HEADER = "x-sbg-version";
  const DEFAULT_DETECTION_TIMEOUT_MS = 5e3;
  let cachedVersion;
  let detectionWaiters = [];
  function normalizeVersion(raw) {
    return raw.split("-")[0];
  }
  function recordCapturedVersion(raw) {
    if (cachedVersion) return;
    cachedVersion = normalizeVersion(raw);
    const resolved = detectionWaiters;
    detectionWaiters = [];
    for (const resolve of resolved) resolve();
  }
  function installGameVersionCapture() {
    const originalFetch2 = window.fetch;
    window.fetch = function patchedFetch(...args) {
      const responsePromise = originalFetch2.apply(this, args);
      void responsePromise.then(
        (response) => {
          const raw = response.headers.get(VERSION_HEADER);
          if (raw) recordCapturedVersion(raw);
        },
        () => {
        }
      );
      return responsePromise;
    };
  }
  function initGameVersionDetection(timeoutMs = DEFAULT_DETECTION_TIMEOUT_MS) {
    if (cachedVersion !== void 0) return Promise.resolve();
    return new Promise((resolve) => {
      const waiter = () => {
        clearTimeout(timer);
        resolve();
      };
      const timer = setTimeout(() => {
        if (cachedVersion === void 0) cachedVersion = null;
        const idx = detectionWaiters.indexOf(waiter);
        if (idx !== -1) detectionWaiters.splice(idx, 1);
        resolve();
      }, timeoutMs);
      detectionWaiters.push(waiter);
    });
  }
  function getDetectedVersion() {
    if (cachedVersion === void 0) {
      console.warn(
        "[SVP] getDetectedVersion() вызвана до initGameVersionDetection(); возвращаю null."
      );
      return null;
    }
    return cachedVersion;
  }
  function compareVersions(a, b) {
    const partsA = a.split(".").map(Number);
    const partsB = b.split(".").map(Number);
    const length = Math.max(partsA.length, partsB.length);
    for (let i = 0; i < length; i++) {
      const diff = (partsA[i] ?? 0) - (partsB[i] ?? 0);
      if (diff !== 0) return diff;
    }
    return 0;
  }
  function isSbgGreaterThan(version) {
    const detected = getDetectedVersion();
    if (detected === null) return false;
    return compareVersions(detected, version) > 0;
  }
  const NATIVE_SINCE_061 = /* @__PURE__ */ new Set([
    "favoritedPoints",
    "inventoryCleanup",
    "keyCountOnPoints",
    "repairAtFullCharge",
    "ngrsZoom",
    "singleFingerRotation",
    "nextPointNavigation"
  ]);
  function isModuleNativeInCurrentGame(moduleId) {
    return isSbgGreaterThan("0.6.0") && NATIVE_SINCE_061.has(moduleId);
  }
  const CONFLICTS_WITH_061 = /* @__PURE__ */ new Set(["swipeToClosePopup"]);
  function isModuleConflictingWithCurrentGame(moduleId) {
    return isSbgGreaterThan("0.6.0") && CONFLICTS_WITH_061.has(moduleId);
  }
  function isSbgScout() {
    return navigator.userAgent.includes("SbgScout/");
  }
  const DISALLOWED_IN_SCOUT = /* @__PURE__ */ new Set(["keepScreenOn"]);
  function isModuleDisallowedInCurrentHost(moduleId) {
    return isSbgScout() && DISALLOWED_IN_SCOUT.has(moduleId);
  }
  function getGameLocale() {
    try {
      const raw = localStorage.getItem("settings");
      if (raw) {
        const parsed = JSON.parse(raw);
        if (typeof parsed === "object" && parsed !== null && "lang" in parsed) {
          if (parsed.lang === "ru") return "ru";
          if (parsed.lang === "sys" && navigator.language.startsWith("ru")) return "ru";
        }
      }
    } catch {
    }
    return "en";
  }
  function t(str) {
    return str[getGameLocale()];
  }
  const SETTINGS_VERSION = 4;
  const DEFAULT_SETTINGS = {
    version: SETTINGS_VERSION,
    modules: {},
    errors: {}
  };
  const STORAGE_KEY$3 = "svp_settings";
  const BACKUP_PREFIX = "svp_settings_backup_v";
  const migrations = [
    // v1 → v2: добавлено поле errors
    (s) => ({ ...s, errors: {} }),
    // v2 → v3: переименование модуля collapsibleTopPanel → enhancedMainScreen
    (s) => {
      const modules = { ...s.modules };
      if ("collapsibleTopPanel" in modules) {
        modules["enhancedMainScreen"] = modules["collapsibleTopPanel"];
        delete modules["collapsibleTopPanel"];
      }
      const errors = { ...s.errors };
      if ("collapsibleTopPanel" in errors) {
        errors["enhancedMainScreen"] = errors["collapsibleTopPanel"];
        delete errors["collapsibleTopPanel"];
      }
      return { ...s, modules, errors };
    },
    // v3 → v4: слияние disableDoubleTapZoom в ngrsZoom.
    // Если у пользователя был включён хотя бы один из двух — новый ngrsZoom включён.
    // Если оба были явно выключены — выключен. Если пользователь не трогал ни один из
    // них — не создаём запись, defaultEnabled сработает при следующей загрузке.
    (s) => {
      const modules = { ...s.modules };
      const hasLegacy = "disableDoubleTapZoom" in modules || "ngrsZoom" in modules;
      if (hasLegacy) {
        const legacyOn = modules["disableDoubleTapZoom"] ?? false;
        const ngrsOn = modules["ngrsZoom"] ?? false;
        modules["ngrsZoom"] = legacyOn || ngrsOn;
      }
      delete modules["disableDoubleTapZoom"];
      const errors = { ...s.errors };
      delete errors["disableDoubleTapZoom"];
      return { ...s, modules, errors };
    }
  ];
  function isSvpSettings(val) {
    return typeof val === "object" && val !== null && "version" in val && typeof val.version === "number" && "modules" in val && typeof val.modules === "object" && val.modules !== null;
  }
  function migrate(settings) {
    let current = { ...settings };
    for (let v = current.version; v < SETTINGS_VERSION; v++) {
      const idx = v - 1;
      if (idx >= 0 && idx < migrations.length) {
        current = migrations[idx](current);
      }
      current.version = v + 1;
    }
    return current;
  }
  function loadSettings() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY$3);
      if (!raw) return { ...DEFAULT_SETTINGS };
      const parsed = JSON.parse(raw);
      if (!isSvpSettings(parsed)) return { ...DEFAULT_SETTINGS };
      if (parsed.version < SETTINGS_VERSION) {
        localStorage.setItem(BACKUP_PREFIX + String(parsed.version), raw);
        const migrated = migrate(parsed);
        saveSettings(migrated);
        return migrated;
      }
      return parsed;
    } catch {
      return { ...DEFAULT_SETTINGS };
    }
  }
  function saveSettings(settings) {
    try {
      localStorage.setItem(STORAGE_KEY$3, JSON.stringify(settings));
      return true;
    } catch (error) {
      console.error("[SVP] Не удалось сохранить настройки в localStorage:", error);
      return false;
    }
  }
  function persistModuleDefaults(settings, modules) {
    let updated = settings;
    for (const mod of modules) {
      if (!(mod.id in updated.modules)) {
        updated = setModuleEnabled(updated, mod.id, mod.defaultEnabled);
      }
    }
    return updated;
  }
  function isModuleEnabled(settings, id, defaultEnabled) {
    return settings.modules[id] ?? defaultEnabled;
  }
  function setModuleEnabled(settings, id, enabled2) {
    return {
      ...settings,
      modules: { ...settings.modules, [id]: enabled2 }
    };
  }
  function setModuleError(settings, id, message) {
    return {
      ...settings,
      errors: { ...settings.errors, [id]: message }
    };
  }
  function clearModuleError(settings, id) {
    const errors = Object.fromEntries(Object.entries(settings.errors).filter(([key]) => key !== id));
    return { ...settings, errors };
  }
  const PHASE_LABELS = {
    init: "инициализации",
    enable: "включении",
    disable: "выключении"
  };
  function handleModuleError(mod, phase, e, onError) {
    const message = e instanceof Error ? e.message : String(e);
    console.error(`[SVP] Ошибка при ${PHASE_LABELS[phase]} модуля "${t(mod.name)}":`, e);
    mod.status = "failed";
    onError == null ? void 0 : onError(mod.id, message);
  }
  let registeredModules = [];
  function registerModules(modules) {
    registeredModules = modules;
  }
  function getModuleById(id) {
    return registeredModules.find((mod) => mod.id === id);
  }
  function isModuleActive(id) {
    const mod = getModuleById(id);
    if (!mod) return false;
    if (mod.status !== "ready") return false;
    const settings = loadSettings();
    return isModuleEnabled(settings, id, mod.defaultEnabled);
  }
  function runModuleAction(action, onError) {
    try {
      const result = action();
      if (result instanceof Promise) {
        return result.catch(onError);
      }
    } catch (e) {
      onError(e);
    }
  }
  function initModules(modules, isEnabled, onError, onReady) {
    for (const mod of modules) {
      const initErrorHandler = (e) => {
        handleModuleError(mod, "init", e, onError);
      };
      const enableErrorHandler = (e) => {
        handleModuleError(mod, "enable", e, onError);
      };
      const markReady = () => {
        if (mod.status !== "failed") {
          mod.status = "ready";
          onReady == null ? void 0 : onReady(mod.id);
        }
      };
      const enableIfNeeded = () => {
        if (mod.status === "failed" || !isEnabled(mod.id)) {
          markReady();
          return;
        }
        const result = runModuleAction(mod.enable.bind(mod), enableErrorHandler);
        if (result instanceof Promise) {
          void result.then(markReady);
          return;
        }
        markReady();
      };
      const initResult = runModuleAction(mod.init.bind(mod), initErrorHandler);
      if (initResult instanceof Promise) {
        void initResult.then(enableIfNeeded);
      } else {
        enableIfNeeded();
      }
    }
  }
  const MAX_ENTRIES = 50;
  const entries = [];
  function addEntry(level, message) {
    entries.push({ timestamp: Date.now(), level, message });
    if (entries.length > MAX_ENTRIES) {
      entries.shift();
    }
  }
  function formatArgs(args) {
    return args.map((argument) => {
      if (argument instanceof Error) {
        return argument.stack ?? argument.message;
      }
      return String(argument);
    }).join(" ");
  }
  let teardown = null;
  function initErrorLog() {
    if (teardown) teardown();
    const originalError = console.error;
    const originalWarn = console.warn;
    console.error = (...args) => {
      addEntry("error", formatArgs(args));
      originalError.apply(console, args);
    };
    console.warn = (...args) => {
      addEntry("warn", formatArgs(args));
      originalWarn.apply(console, args);
    };
    function onError(event) {
      const message = event.error instanceof Error ? event.error.stack ?? event.error.message : event.message;
      addEntry("uncaught", message);
    }
    function onUnhandledRejection(event) {
      const reason = event.reason;
      const message = reason instanceof Error ? reason.stack ?? reason.message : String(reason);
      addEntry("uncaught", message);
    }
    window.addEventListener("error", onError);
    window.addEventListener("unhandledrejection", onUnhandledRejection);
    teardown = () => {
      console.error = originalError;
      console.warn = originalWarn;
      window.removeEventListener("error", onError);
      window.removeEventListener("unhandledrejection", onUnhandledRejection);
      teardown = null;
    };
  }
  function formatErrorLog() {
    if (entries.length === 0) return "";
    return entries.map((entry) => {
      const time = new Date(entry.timestamp).toISOString();
      return `[${time}] [${entry.level}] ${entry.message}`;
    }).join("\n");
  }
  const REPO_URL = "https://github.com/wrager/sbg-vanilla-plus";
  function buildModuleList(modules) {
    const settings = loadSettings();
    return modules.map((mod) => {
      const enabled2 = isModuleEnabled(settings, mod.id, mod.defaultEnabled);
      return `${enabled2 ? "✅" : "⬜"} ${mod.id}`;
    }).join("\n");
  }
  function buildBugReportUrl(modules) {
    const params = new URLSearchParams({
      template: "bug_report.yml",
      version: "0.10.1",
      browser: navigator.userAgent,
      modules: buildModuleList(modules)
    });
    return `${REPO_URL}/issues/new?${params.toString()}`;
  }
  function buildDiagnosticClipboard(modules) {
    const settings = loadSettings();
    const sections = [];
    const moduleErrors = modules.filter((mod) => settings.errors[mod.id]).map((mod) => `${mod.id}: ${settings.errors[mod.id]}`).join("\n");
    if (moduleErrors) {
      sections.push(`Ошибки модулей:
${moduleErrors}`);
    }
    const errorLog = formatErrorLog();
    if (errorLog) {
      sections.push(`Лог консоли:
${errorLog}`);
    }
    if (sections.length === 0) {
      return "Ошибок не обнаружено";
    }
    return sections.join("\n\n");
  }
  function $(selector, root = document) {
    return root.querySelector(selector);
  }
  function $$(selector, root = document) {
    return [...root.querySelectorAll(selector)];
  }
  function waitForElement(selector, timeout = 1e4) {
    return new Promise((resolve, reject) => {
      const existing = $(selector);
      if (existing) {
        resolve(existing);
        return;
      }
      const observer2 = new MutationObserver(() => {
        const el = $(selector);
        if (el) {
          observer2.disconnect();
          clearTimeout(timer);
          resolve(el);
        }
      });
      observer2.observe(document.documentElement, {
        childList: true,
        subtree: true
      });
      const timer = setTimeout(() => {
        observer2.disconnect();
        reject(new Error(`[SVP] Элемент "${selector}" не найден за ${timeout}мс`));
      }, timeout);
    });
  }
  function injectStyles(css2, id) {
    removeStyles(id);
    const style = document.createElement("style");
    style.id = `svp-${id}`;
    style.textContent = css2;
    document.head.appendChild(style);
  }
  function removeStyles(id) {
    var _a;
    (_a = document.getElementById(`svp-${id}`)) == null ? void 0 : _a.remove();
  }
  const TOAST_CLASS = "svp-toast";
  const TOAST_HIDE_CLASS = "svp-toast-hide";
  const DEFAULT_DURATION = 3e3;
  function showToast(message, duration = DEFAULT_DURATION) {
    const toast = document.createElement("div");
    toast.className = TOAST_CLASS;
    toast.textContent = message;
    const dismiss = () => {
      if (toast.classList.contains(TOAST_HIDE_CLASS)) return;
      toast.classList.add(TOAST_HIDE_CLASS);
      toast.addEventListener("transitionend", () => {
        toast.remove();
      });
    };
    toast.addEventListener("click", dismiss);
    document.body.appendChild(toast);
    setTimeout(dismiss, duration);
  }
  function persistOrNotify(settings) {
    if (saveSettings(settings)) return true;
    showToast(
      t({
        en: "Failed to save settings (storage full or inaccessible)",
        ru: "Не удалось сохранить настройки (хранилище заполнено или недоступно)"
      })
    );
    return false;
  }
  const PANEL_ID$1 = "svp-settings-panel";
  const GAME_SETTINGS_ENTRY_ID = "svp-game-settings-entry";
  const PANEL_STYLES = `
.svp-settings-panel {
  position: fixed;
  inset: 0;
  z-index: 10000;
  background: var(--background);
  color: var(--text);
  display: none;
  flex-direction: column;
  font-size: 13px;
}

.svp-settings-panel.svp-open {
  display: flex;
}

.svp-settings-header,
.svp-settings-content,
.svp-settings-footer {
  max-width: 600px;
  margin-left: auto;
  margin-right: auto;
  width: 100%;
  box-sizing: border-box;
}

.svp-settings-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  font-weight: bold;
  padding: 4px 8px;
  flex-shrink: 0;
  border-bottom: 1px solid var(--border-transp);
}

.svp-settings-header.svp-scroll-top {
  box-shadow: 0 4px 8px rgba(0, 0, 0, 0.2);
}

.svp-settings-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.svp-settings-content.svp-scroll-bottom {
  box-shadow: inset 0 -12px 8px -8px rgba(0, 0, 0, 0.2);
}

.svp-settings-panel .svp-settings-close {
  position: fixed;
  bottom: 8px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 1;
  font-size: 1.5em;
  padding: 0 .1em;
}

.svp-settings-section {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.svp-settings-section-title {
  font-size: 10px;
  font-weight: 600;
  color: var(--text);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 6px 0 2px;
  border-bottom: 1px solid var(--border-transp);
  margin-bottom: 2px;
}

.svp-module-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px solid var(--border-transp);
}

.svp-module-info {
  flex: 1;
}

.svp-module-name-line {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.svp-module-name {
  font-size: 13px;
  font-weight: 600;
}

.svp-module-id {
  font-size: 8px;
  color: var(--text-disabled);
  font-family: monospace;
}

.svp-module-desc {
  font-size: 10px;
  color: var(--text);
  margin-top: 1px;
}

.svp-module-failed {
  color: var(--accent);
  font-size: 10px;
  overflow-wrap: break-word;
  word-break: break-word;
}

.svp-module-reload {
  font-size: 10px;
  color: var(--text);
}

.svp-module-reload-text {
  font-style: italic;
}

.svp-module-row-render-error {
  padding: 4px 0;
  color: var(--accent);
  font-size: 10px;
  font-family: monospace;
  border-bottom: 1px dashed var(--border-transp);
  overflow-wrap: break-word;
  word-break: break-word;
}

.svp-module-row-host-provided .svp-module-name,
.svp-module-row-host-provided .svp-module-desc,
.svp-module-row-native-in-game .svp-module-name,
.svp-module-row-native-in-game .svp-module-desc,
.svp-module-row-conflicting-with-game .svp-module-name,
.svp-module-row-conflicting-with-game .svp-module-desc {
  color: var(--text-disabled);
}

.svp-module-row-host-provided-label,
.svp-module-row-native-in-game-label,
.svp-module-row-conflicting-with-game-label {
  font-size: 10px;
  font-style: italic;
  color: var(--text-disabled);
  margin-top: 2px;
}

.svp-module-checkbox,
.svp-toggle-all-checkbox {
  flex-shrink: 0;
  cursor: pointer;
  width: 16px;
  height: 16px;
}

.svp-settings-footer {
  flex-shrink: 0;
  padding: 6px 8px 40px;
  border-top: 1px solid var(--border-transp);
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.svp-settings-version {
  font-size: 10px;
  color: var(--text-disabled);
  font-family: monospace;
}

.svp-toggle-all {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  font-weight: normal;
  font-size: 11px;
  color: var(--text);
}

.svp-report-button {
  background: none;
  border: 1px solid var(--border);
  color: var(--text);
  border-radius: 4px;
  padding: 3px 10px;
  font-size: 11px;
  cursor: pointer;
}
`;
  const CATEGORY_ORDER = ["ui", "feature", "map", "utility", "fix"];
  const SETTINGS_TITLE = {
    en: "SBG Vanilla+ Settings",
    ru: "Настройки SBG Vanilla+"
  };
  const SETTINGS_GAME_ENTRY_LABEL = {
    en: "SBG Vanilla+ settings",
    ru: "Настройки SBG Vanilla+"
  };
  const RELOAD_LABEL = {
    en: "Page will reload on toggle",
    ru: "При переключении происходит перезагрузка"
  };
  const OPEN_LABEL = {
    en: "Open",
    ru: "Открыть"
  };
  const TOGGLE_ALL_LABEL = {
    en: "Toggle all",
    ru: "Переключить все"
  };
  const CATEGORY_LABELS = {
    ui: { en: "Interface", ru: "Интерфейс" },
    map: { en: "Map", ru: "Карта" },
    feature: { en: "Features", ru: "Фичи" },
    utility: { en: "Utilities", ru: "Утилиты" },
    fix: { en: "Bugfixes", ru: "Багфиксы" }
  };
  const UNAVAILABLE_SECTION_LABEL = {
    en: "Unavailable",
    ru: "Недоступные"
  };
  function isModuleUnavailable(moduleId) {
    return isModuleDisallowedInCurrentHost(moduleId) || isModuleNativeInCurrentGame(moduleId) || isModuleConflictingWithCurrentGame(moduleId);
  }
  function createUnavailableRow(mod, errorMessage) {
    if (isModuleDisallowedInCurrentHost(mod.id)) {
      return createHostProvidedRow(mod, errorMessage);
    }
    if (isModuleNativeInCurrentGame(mod.id)) {
      return createNativeInGameRow(mod, errorMessage);
    }
    if (isModuleConflictingWithCurrentGame(mod.id)) {
      return createConflictingWithGameRow(mod, errorMessage);
    }
    return null;
  }
  function createCheckbox(checked, onChange) {
    const input = document.createElement("input");
    input.type = "checkbox";
    input.className = "svp-module-checkbox";
    input.checked = checked;
    input.addEventListener("change", () => {
      onChange(input.checked);
    });
    return input;
  }
  const HOST_PROVIDED_LABEL = {
    en: "Implemented in SBG Scout",
    ru: "Реализовано в SBG Scout"
  };
  const NATIVE_IN_GAME_LABEL = {
    en: "Implemented natively in the game",
    ru: "Реализовано в игре"
  };
  const CONFLICTING_WITH_GAME_LABEL = {
    en: "Conflicts with the new version of the game",
    ru: "Конфликтует с новой версией игры"
  };
  function createHostProvidedRow(mod, errorMessage) {
    const row = document.createElement("div");
    row.className = "svp-module-row svp-module-row-host-provided";
    const info = document.createElement("div");
    info.className = "svp-module-info";
    const nameLine = document.createElement("div");
    nameLine.className = "svp-module-name-line";
    const name = document.createElement("div");
    name.className = "svp-module-name";
    name.textContent = t(mod.name);
    const modId = document.createElement("div");
    modId.className = "svp-module-id";
    modId.textContent = mod.id;
    nameLine.appendChild(name);
    nameLine.appendChild(modId);
    const desc = document.createElement("div");
    desc.className = "svp-module-desc";
    desc.textContent = t(mod.description);
    const hostLabel = document.createElement("div");
    hostLabel.className = "svp-module-row-host-provided-label";
    hostLabel.textContent = t(HOST_PROVIDED_LABEL);
    info.appendChild(nameLine);
    info.appendChild(desc);
    info.appendChild(hostLabel);
    const failed = document.createElement("div");
    failed.className = "svp-module-failed";
    function setError(message) {
      if (message) {
        failed.textContent = message;
        failed.style.display = "";
      } else {
        failed.textContent = "";
        failed.style.display = "none";
      }
    }
    setError(errorMessage);
    info.appendChild(failed);
    row.appendChild(info);
    return { row, setError };
  }
  function createConflictingWithGameRow(mod, errorMessage) {
    const row = document.createElement("div");
    row.className = "svp-module-row svp-module-row-conflicting-with-game";
    const info = document.createElement("div");
    info.className = "svp-module-info";
    const nameLine = document.createElement("div");
    nameLine.className = "svp-module-name-line";
    const name = document.createElement("div");
    name.className = "svp-module-name";
    name.textContent = t(mod.name);
    const modId = document.createElement("div");
    modId.className = "svp-module-id";
    modId.textContent = mod.id;
    nameLine.appendChild(name);
    nameLine.appendChild(modId);
    const desc = document.createElement("div");
    desc.className = "svp-module-desc";
    desc.textContent = t(mod.description);
    const conflictLabel = document.createElement("div");
    conflictLabel.className = "svp-module-row-conflicting-with-game-label";
    conflictLabel.textContent = t(CONFLICTING_WITH_GAME_LABEL);
    info.appendChild(nameLine);
    info.appendChild(desc);
    info.appendChild(conflictLabel);
    const failed = document.createElement("div");
    failed.className = "svp-module-failed";
    function setError(message) {
      if (message) {
        failed.textContent = message;
        failed.style.display = "";
      } else {
        failed.textContent = "";
        failed.style.display = "none";
      }
    }
    setError(errorMessage);
    info.appendChild(failed);
    row.appendChild(info);
    return { row, setError };
  }
  function createNativeInGameRow(mod, errorMessage) {
    const row = document.createElement("div");
    row.className = "svp-module-row svp-module-row-native-in-game";
    const info = document.createElement("div");
    info.className = "svp-module-info";
    const nameLine = document.createElement("div");
    nameLine.className = "svp-module-name-line";
    const name = document.createElement("div");
    name.className = "svp-module-name";
    name.textContent = t(mod.name);
    const modId = document.createElement("div");
    modId.className = "svp-module-id";
    modId.textContent = mod.id;
    nameLine.appendChild(name);
    nameLine.appendChild(modId);
    const desc = document.createElement("div");
    desc.className = "svp-module-desc";
    desc.textContent = t(mod.description);
    const gameLabel = document.createElement("div");
    gameLabel.className = "svp-module-row-native-in-game-label";
    gameLabel.textContent = t(NATIVE_IN_GAME_LABEL);
    info.appendChild(nameLine);
    info.appendChild(desc);
    info.appendChild(gameLabel);
    const failed = document.createElement("div");
    failed.className = "svp-module-failed";
    function setError(message) {
      if (message) {
        failed.textContent = message;
        failed.style.display = "";
      } else {
        failed.textContent = "";
        failed.style.display = "none";
      }
    }
    setError(errorMessage);
    info.appendChild(failed);
    row.appendChild(info);
    return { row, setError };
  }
  function createModuleRow(mod, enabled2, onChange, errorMessage) {
    const row = document.createElement("div");
    row.className = "svp-module-row";
    const info = document.createElement("div");
    info.className = "svp-module-info";
    const nameLine = document.createElement("div");
    nameLine.className = "svp-module-name-line";
    const name = document.createElement("div");
    name.className = "svp-module-name";
    name.textContent = t(mod.name);
    const modId = document.createElement("div");
    modId.className = "svp-module-id";
    modId.textContent = mod.id;
    nameLine.appendChild(name);
    nameLine.appendChild(modId);
    const desc = document.createElement("div");
    desc.className = "svp-module-desc";
    desc.textContent = t(mod.description);
    info.appendChild(nameLine);
    info.appendChild(desc);
    if (mod.requiresReload) {
      const reloadIndicator = document.createElement("div");
      reloadIndicator.className = "svp-module-reload";
      reloadIndicator.textContent = "↻ ";
      const reloadText = document.createElement("span");
      reloadText.className = "svp-module-reload-text";
      reloadText.textContent = t(RELOAD_LABEL);
      reloadIndicator.appendChild(reloadText);
      info.appendChild(reloadIndicator);
    }
    const failed = document.createElement("div");
    failed.className = "svp-module-failed";
    row.appendChild(info);
    const checkbox2 = createCheckbox(enabled2, onChange);
    row.appendChild(checkbox2);
    function setError(message) {
      if (message) {
        failed.textContent = message;
        failed.style.display = "";
      } else {
        failed.textContent = "";
        failed.style.display = "none";
      }
    }
    setError(errorMessage);
    info.appendChild(failed);
    return { row, checkbox: checkbox2, setError };
  }
  function fillSection(section, modules, category, errorDisplay, checkboxMap, onAnyToggle) {
    const title = document.createElement("div");
    title.className = "svp-settings-section-title";
    title.textContent = t(CATEGORY_LABELS[category]);
    section.appendChild(title);
    const initialSettings = loadSettings();
    for (const mod of modules) {
      try {
        const errorMessage = initialSettings.errors[mod.id] ?? null;
        const enabled2 = isModuleEnabled(initialSettings, mod.id, mod.defaultEnabled);
        let checkboxRef = null;
        const { row, checkbox: checkbox2, setError } = createModuleRow(
          mod,
          enabled2,
          (newEnabled) => {
            void handleModuleToggle(
              mod,
              newEnabled,
              (checked) => {
                if (checkboxRef) checkboxRef.checked = checked;
              },
              setError,
              onAnyToggle
            );
          },
          errorMessage
        );
        checkboxRef = checkbox2;
        checkboxMap.set(mod.id, checkbox2);
        errorDisplay.set(mod.id, setError);
        section.appendChild(row);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error(`[SVP] Ошибка рендера настроек модуля "${mod.id}":`, error);
        section.appendChild(createRenderErrorRow(mod.id, message));
      }
    }
  }
  async function handleModuleToggle(mod, newEnabled, setChecked, setError, onAnyToggle) {
    if (!persistOrNotify(setModuleEnabled(loadSettings(), mod.id, newEnabled))) {
      setChecked(!newEnabled);
      onAnyToggle();
      return;
    }
    if (mod.requiresReload) {
      location.hash = "svp-settings";
      location.reload();
      return;
    }
    const phaseLabel = newEnabled ? "включении" : "выключении";
    const toggleAction = newEnabled ? mod.enable.bind(mod) : mod.disable.bind(mod);
    try {
      const result = toggleAction();
      if (result instanceof Promise) {
        await result;
      }
      mod.status = "ready";
      persistOrNotify(clearModuleError(loadSettings(), mod.id));
      setError(null);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[SVP] Ошибка при ${phaseLabel} модуля "${t(mod.name)}":`, error);
      mod.status = "failed";
      const previousEnabled = !newEnabled;
      setChecked(previousEnabled);
      persistOrNotify(setModuleEnabled(loadSettings(), mod.id, previousEnabled));
      persistOrNotify(setModuleError(loadSettings(), mod.id, message));
      setError(message);
    }
    onAnyToggle();
  }
  function fillUnavailableSection(section, modules, errorDisplay) {
    const title = document.createElement("div");
    title.className = "svp-settings-section-title";
    title.textContent = t(UNAVAILABLE_SECTION_LABEL);
    section.appendChild(title);
    const initialSettings = loadSettings();
    for (const mod of modules) {
      try {
        const errorMessage = initialSettings.errors[mod.id] ?? null;
        const unavailableRow = createUnavailableRow(mod, errorMessage);
        if (!unavailableRow) {
          throw new Error(`module "${mod.id}" classified as unavailable but no row renderer matched`);
        }
        errorDisplay.set(mod.id, unavailableRow.setError);
        section.appendChild(unavailableRow.row);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error(`[SVP] Ошибка рендера настроек модуля "${mod.id}":`, error);
        section.appendChild(createRenderErrorRow(mod.id, message));
      }
    }
  }
  function createRenderErrorRow(moduleId, message) {
    const row = document.createElement("div");
    row.className = "svp-module-row-render-error";
    row.dataset["svpModuleId"] = moduleId;
    row.textContent = `${moduleId}: render error — ${message}`;
    return row;
  }
  async function handleToggleAll(modules, enableAll, checkboxMap, errorDisplay) {
    let needsReload = false;
    for (const mod of modules) {
      const checkbox2 = checkboxMap.get(mod.id);
      if (!checkbox2 || checkbox2.checked === enableAll) continue;
      const previousEnabled = !enableAll;
      checkbox2.checked = enableAll;
      if (!persistOrNotify(setModuleEnabled(loadSettings(), mod.id, enableAll))) {
        checkbox2.checked = previousEnabled;
        return;
      }
      if (mod.requiresReload) {
        needsReload = true;
        continue;
      }
      const phaseLabel = enableAll ? "включении" : "выключении";
      const toggleAction = enableAll ? mod.enable.bind(mod) : mod.disable.bind(mod);
      const setError = errorDisplay.get(mod.id);
      try {
        const result = toggleAction();
        if (result instanceof Promise) {
          await result;
        }
        mod.status = "ready";
        persistOrNotify(clearModuleError(loadSettings(), mod.id));
        setError == null ? void 0 : setError(null);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        console.error(`[SVP] Ошибка при ${phaseLabel} модуля "${t(mod.name)}":`, error);
        mod.status = "failed";
        checkbox2.checked = previousEnabled;
        persistOrNotify(setModuleEnabled(loadSettings(), mod.id, previousEnabled));
        persistOrNotify(setModuleError(loadSettings(), mod.id, message));
        setError == null ? void 0 : setError(message);
      }
    }
    if (needsReload) {
      location.hash = "svp-settings";
      location.reload();
    }
  }
  function initSettingsUI(modules, errorDisplay) {
    injectStyles(PANEL_STYLES, "settings");
    const panel2 = document.createElement("div");
    panel2.className = "svp-settings-panel";
    panel2.id = PANEL_ID$1;
    const header = document.createElement("div");
    header.className = "svp-settings-header";
    const toggleAllLabel = document.createElement("label");
    toggleAllLabel.className = "svp-toggle-all";
    const toggleAllCheckbox = document.createElement("input");
    toggleAllCheckbox.type = "checkbox";
    toggleAllCheckbox.className = "svp-toggle-all-checkbox";
    const toggleAllText = document.createElement("span");
    toggleAllText.textContent = t(TOGGLE_ALL_LABEL);
    toggleAllLabel.appendChild(toggleAllCheckbox);
    toggleAllLabel.appendChild(toggleAllText);
    header.appendChild(toggleAllLabel);
    const titleSpan = document.createElement("span");
    titleSpan.textContent = t(SETTINGS_TITLE);
    header.appendChild(titleSpan);
    panel2.appendChild(header);
    const content = document.createElement("div");
    content.className = "svp-settings-content";
    const checkboxMap = /* @__PURE__ */ new Map();
    function updateMasterState() {
      const checkboxes = [...checkboxMap.values()];
      const checkedCount = checkboxes.filter((cb) => cb.checked).length;
      if (checkedCount === 0) {
        toggleAllCheckbox.checked = false;
        toggleAllCheckbox.indeterminate = false;
      } else if (checkedCount === checkboxes.length) {
        toggleAllCheckbox.checked = true;
        toggleAllCheckbox.indeterminate = false;
      } else {
        toggleAllCheckbox.checked = false;
        toggleAllCheckbox.indeterminate = true;
      }
    }
    const regular = [];
    const unavailable = [];
    for (const mod of modules) {
      if (isModuleUnavailable(mod.id)) {
        unavailable.push(mod);
      } else {
        regular.push(mod);
      }
    }
    const grouped = /* @__PURE__ */ new Map();
    for (const mod of regular) {
      const list = grouped.get(mod.category) ?? [];
      list.push(mod);
      grouped.set(mod.category, list);
    }
    for (const category of CATEGORY_ORDER) {
      const categoryModules = grouped.get(category);
      if (!(categoryModules == null ? void 0 : categoryModules.length)) continue;
      const section = document.createElement("div");
      section.className = "svp-settings-section";
      fillSection(section, categoryModules, category, errorDisplay, checkboxMap, updateMasterState);
      content.appendChild(section);
    }
    if (unavailable.length > 0) {
      const section = document.createElement("div");
      section.className = "svp-settings-section svp-settings-section-unavailable";
      fillUnavailableSection(section, unavailable, errorDisplay);
      content.appendChild(section);
    }
    updateMasterState();
    toggleAllCheckbox.addEventListener("change", () => {
      void handleToggleAll(modules, toggleAllCheckbox.checked, checkboxMap, errorDisplay).then(() => {
        updateMasterState();
      });
    });
    panel2.appendChild(content);
    const footer = document.createElement("div");
    footer.className = "svp-settings-footer";
    const version = document.createElement("span");
    version.className = "svp-settings-version";
    version.textContent = `SBG Vanilla+ v${"0.10.1"}`;
    const reportButton = document.createElement("button");
    reportButton.className = "svp-report-button";
    const reportLabel = { en: "Report a bug", ru: "Сообщить об ошибке" };
    reportButton.textContent = t(reportLabel);
    reportButton.addEventListener("click", () => {
      const clipboard = buildDiagnosticClipboard(modules);
      const url = buildBugReportUrl(modules);
      const copiedLabel = { en: "Copied! Opening...", ru: "Скопировано! Открываю..." };
      void navigator.clipboard.writeText(clipboard).then(() => {
        reportButton.textContent = t(copiedLabel);
        setTimeout(() => {
          reportButton.textContent = t(reportLabel);
        }, 2e3);
      });
      window.open(url, "_blank");
    });
    footer.appendChild(reportButton);
    footer.appendChild(version);
    panel2.appendChild(footer);
    const closeButton2 = document.createElement("button");
    closeButton2.className = "svp-settings-close";
    closeButton2.textContent = "[x]";
    closeButton2.addEventListener("click", (event) => {
      event.stopPropagation();
      panel2.classList.remove("svp-open");
    });
    panel2.appendChild(closeButton2);
    function updateScrollIndicators() {
      const hasTop = content.scrollTop > 0;
      const hasBottom = content.scrollTop + content.clientHeight < content.scrollHeight - 1;
      header.classList.toggle("svp-scroll-top", hasTop);
      content.classList.toggle("svp-scroll-bottom", hasBottom);
    }
    content.addEventListener("scroll", updateScrollIndicators);
    const observer2 = new MutationObserver(updateScrollIndicators);
    observer2.observe(content, { childList: true, subtree: true });
    document.body.appendChild(panel2);
    const gameSettingsContent = document.querySelector(".settings-content");
    if (gameSettingsContent) {
      const item = document.createElement("div");
      item.className = "settings-section__item";
      item.id = GAME_SETTINGS_ENTRY_ID;
      const label = document.createElement("span");
      label.textContent = t(SETTINGS_GAME_ENTRY_LABEL);
      const openButton = document.createElement("button");
      openButton.className = "settings-section__button";
      openButton.textContent = t(OPEN_LABEL);
      openButton.addEventListener("click", () => {
        panel2.classList.add("svp-open");
        requestAnimationFrame(updateScrollIndicators);
      });
      item.appendChild(label);
      item.appendChild(openButton);
      let inserted = false;
      if (isSbgScout()) {
        for (const child of gameSettingsContent.querySelectorAll(".settings-section__item")) {
          if (child.textContent.includes("SBG Scout")) {
            child.after(item);
            inserted = true;
            break;
          }
        }
      }
      if (!inserted) {
        gameSettingsContent.prepend(item);
      }
    }
    if (location.hash.includes("svp-settings")) {
      panel2.classList.add("svp-open");
      history.replaceState(null, "", location.pathname + location.search);
      requestAnimationFrame(updateScrollIndicators);
    }
  }
  const toastStyles = ".svp-toast{position:fixed;top:50px;left:50%;transform:translate(-50%);background:var(--background);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:6px 12px;font-size:12px;z-index:10002;opacity:1;transition:opacity .3s ease;max-width:90vw;text-align:center;box-sizing:border-box;cursor:pointer}.svp-toast-hide{opacity:0}";
  let bootstrapped = false;
  function bootstrap(modules) {
    if (bootstrapped) {
      console.warn("[SVP] bootstrap() вызван повторно — игнорирую. Модули уже инициализированы.");
      return;
    }
    bootstrapped = true;
    injectStyles(toastStyles, "svp-toast");
    registerModules(modules);
    let settings = loadSettings();
    settings = persistModuleDefaults(settings, modules);
    const errorDisplay = /* @__PURE__ */ new Map();
    initModules(
      modules,
      (id) => {
        if (isModuleDisallowedInCurrentHost(id)) return false;
        if (isModuleNativeInCurrentGame(id)) return false;
        if (isModuleConflictingWithCurrentGame(id)) return false;
        const mod = modules.find((m) => m.id === id);
        return isModuleEnabled(settings, id, (mod == null ? void 0 : mod.defaultEnabled) ?? true);
      },
      (id, message) => {
        var _a;
        settings = setModuleError(settings, id, message);
        saveSettings(settings);
        (_a = errorDisplay.get(id)) == null ? void 0 : _a(message);
      },
      (id) => {
        var _a;
        if (settings.errors[id]) {
          settings = clearModuleError(settings, id);
          saveSettings(settings);
          (_a = errorDisplay.get(id)) == null ? void 0 : _a(null);
        }
      }
    );
    saveSettings(settings);
    initSettingsUI(modules, errorDisplay);
  }
  function ensureSbgVersionSupported() {
    const detected = getDetectedVersion();
    if (detected === null) return true;
    if (SBG_COMPATIBLE_VERSIONS.includes(detected)) return true;
    const supported = SBG_COMPATIBLE_VERSIONS.join(", ");
    const message = `SBG Vanilla+ не тестировался на версии игры ${detected} (поддерживаются: ${supported}).

ОК — включить скрипт, Отмена — продолжить без скрипта.`;
    return confirm(message);
  }
  function hasTileSource(layer) {
    return "setSource" in layer && typeof layer.setSource === "function";
  }
  function isOlGlobal(val) {
    return typeof val === "object" && val !== null && "Map" in val && (typeof val.Map === "object" || typeof val.Map === "function") && val.Map !== null && "prototype" in val.Map && typeof val.Map.prototype === "object" && val.Map.prototype !== null && "getView" in val.Map.prototype && typeof val.Map.prototype.getView === "function";
  }
  function isDragPan(interaction) {
    var _a, _b;
    const DragPan = (_b = (_a = window.ol) == null ? void 0 : _a.interaction) == null ? void 0 : _b.DragPan;
    return DragPan !== void 0 && interaction instanceof DragPan;
  }
  function findDragPanInteractions(map2) {
    return map2.getInteractions().getArray().filter(isDragPan);
  }
  function createDragPanControl(map2) {
    let disabled = [];
    return {
      disable() {
        disabled = findDragPanInteractions(map2);
        for (const interaction of disabled) {
          interaction.setActive(false);
        }
      },
      restore() {
        for (const interaction of disabled) {
          interaction.setActive(true);
        }
        disabled = [];
      }
    };
  }
  function findLayerByName(map2, name) {
    for (const layer of map2.getLayers().getArray()) {
      if (layer.get("name") === name) return layer;
    }
    return null;
  }
  let captured = null;
  const resolvers = [];
  let hooked = false;
  let proxyInstalled = false;
  const DIAG_DELAY = 5e3;
  function getOlMap() {
    if (captured) return Promise.resolve(captured);
    return new Promise((resolve) => {
      resolvers.push(resolve);
    });
  }
  function hookGetView(ol) {
    hooked = true;
    const proto = ol.Map.prototype;
    const orig = proto.getView;
    proxyInstalled = true;
    proto.getView = new Proxy(orig, {
      apply(_target, thisArg) {
        proto.getView = orig;
        proxyInstalled = false;
        captured = thisArg;
        for (const r of resolvers) r(thisArg);
        resolvers.length = 0;
        return orig.call(thisArg);
      }
    });
  }
  function logDiagnostics() {
    if (captured) return;
    const olAvailable = isOlGlobal(window.ol);
    const viewportExists = document.querySelector(".ol-viewport") !== null;
    console.warn(
      "[SVP] OL Map не захвачен за %dс. Диагностика: window.ol=%s, hookGetView=%s, proxy=%s, viewport=%s",
      DIAG_DELAY / 1e3,
      olAvailable ? "есть" : "нет",
      hooked ? "вызван" : "не вызван",
      proxyInstalled ? "установлен" : "снят",
      viewportExists ? "есть" : "нет"
    );
    if (olAvailable && !hooked) {
      console.warn("[SVP] Повторная попытка перехвата getView");
      hookGetView(window.ol);
    }
  }
  function initOlMapCapture() {
    if (window.ol) {
      hookGetView(window.ol);
    } else {
      let olValue;
      Object.defineProperty(window, "ol", {
        configurable: true,
        enumerable: true,
        get() {
          return olValue;
        },
        set(val) {
          Object.defineProperty(window, "ol", {
            configurable: true,
            enumerable: true,
            writable: true,
            value: val
          });
          if (isOlGlobal(val)) {
            olValue = val;
            hookGetView(val);
          }
        }
      });
    }
    setTimeout(logDiagnostics, DIAG_DELAY);
  }
  const FLAVOR_HEADER = "x-sbg-flavor";
  const FLAVOR_VALUE = `VanillaPlus/${"0.10.1"}`;
  function installSbgFlavor() {
    const originalFetch2 = window.fetch;
    window.fetch = function(input, init) {
      const headers = new Headers(init == null ? void 0 : init.headers);
      const existing = headers.get(FLAVOR_HEADER);
      if (existing) {
        const flavors = existing.split(" ");
        if (!flavors.includes(FLAVOR_VALUE)) {
          flavors.push(FLAVOR_VALUE);
        }
        headers.set(FLAVOR_HEADER, flavors.join(" "));
      } else {
        headers.set(FLAVOR_HEADER, FLAVOR_VALUE);
      }
      return originalFetch2.call(this, input, { ...init, headers });
    };
  }
  const css$1 = ".topleft-container.svp-compact{top:.45em;left:.45em}.topleft-container.svp-compact .self-info{display:flex!important;align-items:center!important;justify-content:flex-start!important;text-align:left!important;width:max-content!important;margin:0;padding:0!important;border:none!important;background:none!important;font-size:.9em}#attack-menu{position:fixed;left:50%;transform:translate(-50%);height:27pt}";
  const MODULE_ID$j = "enhancedMainScreen";
  let cleanup$1 = null;
  function isHTMLElement(element) {
    return element instanceof HTMLElement;
  }
  function retranslateI18n(element) {
    const globals = window;
    const jq = globals.$;
    if (typeof jq !== "function") return;
    const wrapped = jq(element);
    if (typeof wrapped !== "object" || wrapped === null) return;
    const localize = wrapped.localize;
    if (typeof localize === "function") {
      localize.call(wrapped);
    }
  }
  function i18nextTranslate(key) {
    if (key === null) return null;
    const globals = window;
    const i18next = globals.i18next;
    if (typeof i18next !== "object" || i18next === null) return null;
    const translate = i18next.t;
    if (typeof translate !== "function") return null;
    const result = translate.call(i18next, key);
    return typeof result === "string" ? result : null;
  }
  function restoreI18nText(element, originalText, i18nKey) {
    const translated = i18nextTranslate(i18nKey);
    const restored = translated ?? originalText;
    if (restored !== null) {
      element.textContent = restored;
    }
    if (i18nKey !== null) {
      element.setAttribute("data-i18n", i18nKey);
    }
    retranslateI18n(element);
  }
  function setupOpsInventory(container, opsButton) {
    const invSpan = $("#self-info__inv", container);
    const limSpan = $("#self-info__inv-lim", container);
    const invEntry = invSpan == null ? void 0 : invSpan.closest(".self-info__entry");
    const opsOriginalText = opsButton.textContent;
    const opsI18nKey = opsButton.getAttribute("data-i18n");
    opsButton.removeAttribute("data-i18n");
    const update = () => {
      const inv = (invSpan == null ? void 0 : invSpan.textContent) ?? "?";
      const lim = (limSpan == null ? void 0 : limSpan.textContent) ?? "?";
      opsButton.textContent = `${inv}/${lim}`;
      if (isHTMLElement(invEntry)) {
        opsButton.style.color = invEntry.style.color;
      }
    };
    update();
    const observer2 = new MutationObserver(update);
    if (invSpan) observer2.observe(invSpan, { childList: true, characterData: true, subtree: true });
    if (limSpan) observer2.observe(limSpan, { childList: true, characterData: true, subtree: true });
    if (isHTMLElement(invEntry)) {
      observer2.observe(invEntry, { attributes: true, attributeFilter: ["style"] });
    }
    return {
      destroy: () => {
        observer2.disconnect();
        opsButton.style.color = "";
        restoreI18nText(opsButton, opsOriginalText, opsI18nKey);
      }
    };
  }
  async function setup() {
    const container = await waitForElement(".topleft-container");
    if (!isHTMLElement(container)) return () => {
    };
    const selfInfo = $(".self-info", container);
    if (!isHTMLElement(selfInfo)) return () => {
    };
    const opsButton = $("#ops", container);
    if (!isHTMLElement(opsButton)) return () => {
    };
    const nameSpan = $("#self-info__name", container);
    const nameSpanParent = nameSpan == null ? void 0 : nameSpan.parentElement;
    const nameSpanNextSibling = (nameSpan == null ? void 0 : nameSpan.nextSibling) ?? null;
    const allEntries = $$(".self-info__entry", container).filter(isHTMLElement);
    const hiddenElements = [...allEntries];
    for (const element of hiddenElements) {
      element.style.display = "none";
    }
    if (nameSpan) {
      selfInfo.appendChild(nameSpan);
    }
    const opsInventory = setupOpsInventory(container, opsButton);
    const gameMenu = $(".game-menu", container);
    if (isHTMLElement(gameMenu)) {
      container.insertBefore(gameMenu, selfInfo);
    }
    const settingsButton = $("#settings", container);
    const settingsOriginalText = (settingsButton == null ? void 0 : settingsButton.textContent) ?? null;
    const settingsI18nKey = (settingsButton == null ? void 0 : settingsButton.getAttribute("data-i18n")) ?? null;
    if (isHTMLElement(settingsButton)) {
      settingsButton.textContent = "⚙︎";
      settingsButton.removeAttribute("data-i18n");
    }
    container.classList.add("svp-compact");
    return () => {
      opsInventory.destroy();
      if (isHTMLElement(settingsButton)) {
        restoreI18nText(settingsButton, settingsOriginalText, settingsI18nKey);
      }
      if (nameSpan && nameSpanParent) {
        if (nameSpanNextSibling) {
          nameSpanParent.insertBefore(nameSpan, nameSpanNextSibling);
        } else {
          nameSpanParent.appendChild(nameSpan);
        }
      }
      if (isHTMLElement(gameMenu)) {
        selfInfo.after(gameMenu);
      }
      for (const element of hiddenElements) {
        element.style.display = "";
      }
      container.classList.remove("svp-compact");
    };
  }
  const enhancedMainScreen = {
    id: MODULE_ID$j,
    name: { en: "Enhanced Main Screen", ru: "Улучшенный главный экран" },
    description: {
      en: "Compacts the top panel: nick below buttons, inventory in OPS, gear icon for Settings, attack button centered",
      ru: "Компактная верхняя панель: ник под кнопками, инвентарь в ОРПЦ, шестерёнка вместо «Настройки», кнопка атаки по центру"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    async enable() {
      injectStyles(css$1, MODULE_ID$j);
      cleanup$1 = await setup();
    },
    disable() {
      removeStyles(MODULE_ID$j);
      cleanup$1 == null ? void 0 : cleanup$1();
      cleanup$1 = null;
    }
  };
  const styles$7 = ".info.popup .i-buttons button{min-height:72px;display:flex;align-items:center;justify-content:center}.i-stat__entry:not(.i-stat__cores){font-size:.7rem}.cores-list__level{font-size:1rem}#magic-deploy-btn{position:fixed;bottom:5px;left:5px;width:32px;height:32px;min-height:auto}";
  const MODULE_ID$i = "enhancedPointPopupUi";
  const enhancedPointPopupUi = {
    id: MODULE_ID$i,
    name: { en: "Enhanced Point Popup UI", ru: "Улучшенный UI попапа точки" },
    description: {
      en: "Larger buttons, smaller text, auto-deploy hidden from accidental taps",
      ru: "Крупные кнопки, мелкий текст, авто-простановка убрана от случайных нажатий"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    enable() {
      injectStyles(styles$7, MODULE_ID$i);
    },
    disable() {
      removeStyles(MODULE_ID$i);
    }
  };
  const styles$6 = ".info.popup{touch-action:pan-y}.info.popup .deploy-slider-wrp{touch-action:manipulation}.info.popup.svp-swipe-animating{transition:translate .3s ease-out,rotate .3s ease-out,opacity .3s ease-out}";
  const MODULE_ID$h = "swipeToClosePopup";
  const POPUP_SELECTOR$1 = ".info.popup";
  const DIRECTION_THRESHOLD = 10;
  const DISMISS_THRESHOLD = 100;
  const VELOCITY_THRESHOLD = 0.5;
  const MAX_ROTATION = 8;
  const OPACITY_DISTANCE = 250;
  const ANIMATION_DURATION = 300;
  const ANIMATION_SAFETY_MARGIN = 50;
  let state$1 = "idle";
  let startX = 0;
  let startY = 0;
  let currentDeltaX = 0;
  let startTimestamp = 0;
  let popup = null;
  let safetyTimer = null;
  let animationFrameId = null;
  let classObserver = null;
  let lastObservedGuid = null;
  function applySwipeStyles(element, deltaX) {
    const rotation = deltaX / window.innerWidth * MAX_ROTATION;
    const opacity = Math.max(0, 1 - Math.abs(deltaX) / OPACITY_DISTANCE);
    element.style.setProperty("translate", `${deltaX}px`);
    element.style.setProperty("rotate", `${rotation}deg`);
    element.style.opacity = String(opacity);
  }
  function resetElementStyles(element) {
    element.style.removeProperty("translate");
    element.style.removeProperty("rotate");
    element.style.opacity = "";
    element.style.willChange = "";
    element.classList.remove("svp-swipe-animating");
  }
  function hasStaleSwipeStyles(element) {
    return element.style.getPropertyValue("translate") !== "" || element.style.getPropertyValue("rotate") !== "" || element.style.opacity !== "" || element.classList.contains("svp-swipe-animating");
  }
  function clearSafetyTimer() {
    if (safetyTimer !== null) {
      clearTimeout(safetyTimer);
      safetyTimer = null;
    }
  }
  function cancelAnimationFrameIfPending() {
    if (animationFrameId !== null) {
      cancelAnimationFrame(animationFrameId);
      animationFrameId = null;
    }
  }
  function cleanupAnimation(element = popup) {
    cancelAnimationFrameIfPending();
    clearSafetyTimer();
    if (element) resetElementStyles(element);
    state$1 = "idle";
  }
  function startPopupObserver() {
    if (!popup) return;
    stopPopupObserver();
    lastObservedGuid = popup.dataset.guid ?? null;
    classObserver = new MutationObserver((mutations) => {
      if (!popup) return;
      for (const mutation of mutations) {
        if (mutation.type !== "attributes") continue;
        if (!(mutation.target instanceof HTMLElement)) continue;
        if (mutation.target !== popup) continue;
        if (mutation.attributeName === "class") {
          const oldValue = mutation.oldValue ?? "";
          const wasHidden = /\bhidden\b/.test(oldValue);
          const isHidden = popup.classList.contains("hidden");
          if (!wasHidden || isHidden) continue;
          lastObservedGuid = popup.dataset.guid ?? null;
          if (state$1 !== "idle" || hasStaleSwipeStyles(popup)) cleanupAnimation(popup);
          continue;
        }
        if (mutation.attributeName === "data-guid") {
          if (popup.classList.contains("hidden")) continue;
          const currentGuid = popup.dataset.guid ?? null;
          if (currentGuid === lastObservedGuid) continue;
          lastObservedGuid = currentGuid;
          if (state$1 !== "idle" || hasStaleSwipeStyles(popup)) cleanupAnimation(popup);
        }
      }
    });
    classObserver.observe(popup, {
      attributes: true,
      attributeFilter: ["class", "data-guid"],
      attributeOldValue: true
    });
  }
  function stopPopupObserver() {
    classObserver == null ? void 0 : classObserver.disconnect();
    classObserver = null;
  }
  function animateDismiss(direction) {
    if (!popup) return;
    state$1 = "animating";
    const targetX = direction > 0 ? window.innerWidth : -window.innerWidth;
    const animatingElement = popup;
    animatingElement.classList.add("svp-swipe-animating");
    let finished = false;
    const onTransitionEnd = (event) => {
      if (event.target !== animatingElement) return;
      finish();
    };
    const finish = () => {
      if (finished) return;
      finished = true;
      animatingElement.removeEventListener("transitionend", onTransitionEnd);
      cleanupAnimation(animatingElement);
      const closeButton2 = animatingElement.querySelector(".popup-close");
      if (closeButton2 instanceof HTMLElement) {
        closeButton2.click();
        if (animatingElement.classList.contains("hidden")) return;
      }
      animatingElement.classList.add("hidden");
      for (const toast of animatingElement.querySelectorAll(".toastify")) {
        toast.remove();
      }
    };
    animatingElement.addEventListener("transitionend", onTransitionEnd);
    safetyTimer = setTimeout(finish, ANIMATION_DURATION + ANIMATION_SAFETY_MARGIN);
    animationFrameId = requestAnimationFrame(() => {
      animationFrameId = null;
      applySwipeStyles(animatingElement, targetX);
    });
  }
  function animateReturn() {
    if (!popup) return;
    state$1 = "animating";
    const animatingElement = popup;
    animatingElement.classList.add("svp-swipe-animating");
    let finished = false;
    const onTransitionEnd = (event) => {
      if (event.target !== animatingElement) return;
      finish();
    };
    const finish = () => {
      if (finished) return;
      finished = true;
      animatingElement.removeEventListener("transitionend", onTransitionEnd);
      cleanupAnimation(animatingElement);
    };
    animatingElement.addEventListener("transitionend", onTransitionEnd);
    safetyTimer = setTimeout(finish, ANIMATION_DURATION + ANIMATION_SAFETY_MARGIN);
    animationFrameId = requestAnimationFrame(() => {
      animationFrameId = null;
      animatingElement.style.setProperty("translate", "0px");
      animatingElement.style.setProperty("rotate", "0deg");
      animatingElement.style.opacity = "1";
    });
  }
  function onTouchStart$2(event) {
    if (state$1 === "idle" && popup && hasStaleSwipeStyles(popup)) {
      cleanupAnimation(popup);
    }
    if (state$1 !== "idle") return;
    if (event.targetTouches.length !== 1) return;
    const target = event.target;
    if (!(target instanceof Node)) return;
    const element = target instanceof Element ? target : target.parentElement;
    if (element == null ? void 0 : element.closest(".deploy-slider-wrp")) return;
    const touch = event.targetTouches[0];
    startX = touch.clientX;
    startY = touch.clientY;
    startTimestamp = event.timeStamp;
    currentDeltaX = 0;
    state$1 = "tracking";
    if (popup) {
      popup.style.willChange = "translate, rotate, opacity";
    }
  }
  function onTouchMove$2(event) {
    if (state$1 !== "tracking" && state$1 !== "swiping") return;
    if (event.targetTouches.length !== 1) {
      if (popup) resetElementStyles(popup);
      state$1 = "idle";
      return;
    }
    const touch = event.targetTouches[0];
    const deltaX = touch.clientX - startX;
    const deltaY = touch.clientY - startY;
    if (state$1 === "tracking") {
      if (Math.max(Math.abs(deltaX), Math.abs(deltaY)) < DIRECTION_THRESHOLD) return;
      if (Math.abs(deltaX) > Math.abs(deltaY)) {
        state$1 = "swiping";
      } else {
        if (popup) popup.style.willChange = "";
        state$1 = "idle";
        return;
      }
    }
    event.preventDefault();
    currentDeltaX = deltaX;
    if (popup) applySwipeStyles(popup, deltaX);
  }
  function onTouchEnd$2(event) {
    if (state$1 === "tracking") {
      if (popup) popup.style.willChange = "";
      state$1 = "idle";
      return;
    }
    if (state$1 !== "swiping") return;
    const elapsed = event.timeStamp - startTimestamp;
    const velocity = elapsed > 0 ? Math.abs(currentDeltaX) / elapsed : 0;
    if (Math.abs(currentDeltaX) > DISMISS_THRESHOLD || velocity > VELOCITY_THRESHOLD) {
      animateDismiss(currentDeltaX > 0 ? 1 : -1);
    } else {
      animateReturn();
    }
  }
  function onTouchCancel() {
    if (state$1 === "tracking" || state$1 === "swiping" || state$1 === "animating") {
      cleanupAnimation();
    }
  }
  function addListeners$2() {
    if (!popup) return;
    popup.addEventListener("touchstart", onTouchStart$2, { passive: true });
    popup.addEventListener("touchmove", onTouchMove$2, { passive: false });
    popup.addEventListener("touchend", onTouchEnd$2, { passive: true });
    popup.addEventListener("touchcancel", onTouchCancel, { passive: true });
  }
  function removeListeners$2() {
    if (!popup) return;
    popup.removeEventListener("touchstart", onTouchStart$2);
    popup.removeEventListener("touchmove", onTouchMove$2);
    popup.removeEventListener("touchend", onTouchEnd$2);
    popup.removeEventListener("touchcancel", onTouchCancel);
  }
  const swipeToClosePopup = {
    id: MODULE_ID$h,
    name: {
      en: "Swipe to Close Popup",
      ru: "Закрытие попапа свайпом"
    },
    description: {
      en: "Swipe the point popup left or right to close it with a card swipe animation",
      ru: "Свайп попапа точки влево или вправо закрывает его с анимацией смахивания"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    enable() {
      const element = $(POPUP_SELECTOR$1);
      if (!(element instanceof HTMLElement)) return;
      popup = element;
      injectStyles(styles$6, MODULE_ID$h);
      addListeners$2();
      startPopupObserver();
    },
    disable() {
      removeListeners$2();
      stopPopupObserver();
      cleanupAnimation();
      removeStyles(MODULE_ID$h);
      lastObservedGuid = null;
      popup = null;
    }
  };
  const MODULE_ID$g = "shiftMapCenterDown";
  const PADDING_FACTOR = 0.35;
  const ACTION_PANEL_SELECTORS = ".attack-slider-wrp, .draw-slider-wrp";
  let map$6 = null;
  let topPadding = 0;
  let actionObserver = null;
  let actionPanelActive = false;
  let actionRafId = null;
  let originalCalculateExtent$1 = null;
  function applyPadding(padding) {
    if (!map$6) return;
    const view = map$6.getView();
    const center = view.getCenter();
    view.padding = padding;
    view.setCenter(center);
  }
  function handleActionPanelChange() {
    const openPanel2 = document.querySelector(
      ".attack-slider-wrp:not(.hidden), .draw-slider-wrp:not(.hidden)"
    );
    if (openPanel2 && !actionPanelActive) {
      actionPanelActive = true;
      const panelHeight = openPanel2.getBoundingClientRect().height;
      applyPadding([0, 0, panelHeight, 0]);
    } else if (!openPanel2 && actionPanelActive) {
      actionPanelActive = false;
      applyPadding([topPadding, 0, 0, 0]);
    }
  }
  function startActionObserver() {
    stopActionObserver();
    const panels = document.querySelectorAll(ACTION_PANEL_SELECTORS);
    if (panels.length === 0) return;
    actionObserver = new MutationObserver(() => {
      if (actionRafId !== null) return;
      actionRafId = requestAnimationFrame(() => {
        actionRafId = null;
        handleActionPanelChange();
      });
    });
    for (const panel2 of panels) {
      actionObserver.observe(panel2, {
        attributes: true,
        attributeFilter: ["class"]
      });
    }
  }
  function stopActionObserver() {
    if (actionRafId !== null) {
      cancelAnimationFrame(actionRafId);
      actionRafId = null;
    }
    actionObserver == null ? void 0 : actionObserver.disconnect();
    actionObserver = null;
  }
  function installCalculateExtentWrapper$1() {
    if (!map$6 || originalCalculateExtent$1 !== null) return;
    const view = map$6.getView();
    const original = view.calculateExtent;
    originalCalculateExtent$1 = original;
    view.calculateExtent = (size) => {
      if (size) {
        return original.call(view, [size[0], size[1] + topPadding]);
      }
      return original.call(view, size);
    };
  }
  function restoreCalculateExtentWrapper$1() {
    if (!map$6 || originalCalculateExtent$1 === null) return;
    const view = map$6.getView();
    view.calculateExtent = originalCalculateExtent$1;
    originalCalculateExtent$1 = null;
  }
  const shiftMapCenterDown = {
    id: MODULE_ID$g,
    name: { en: "Shift Map Center Down", ru: "Сдвиг центра карты вниз" },
    description: {
      en: "Moves map center down so you see more ahead while moving",
      ru: "Сдвигает центр карты вниз, чтобы видеть больше карты впереди по ходу движения"
    },
    defaultEnabled: true,
    category: "map",
    init() {
      topPadding = Math.round(window.innerHeight * PADDING_FACTOR);
      originalCalculateExtent$1 = null;
      actionPanelActive = false;
      return getOlMap().then((olMap2) => {
        map$6 = olMap2;
      });
    },
    enable() {
      installCalculateExtentWrapper$1();
      applyPadding([topPadding, 0, 0, 0]);
      startActionObserver();
    },
    disable() {
      stopActionObserver();
      actionPanelActive = false;
      applyPadding([0, 0, 0, 0]);
      restoreCalculateExtentWrapper$1();
    }
  };
  const MODULE_ID$f = "ngrsZoom";
  const TAP_DURATION_THRESHOLD = 200;
  const MAX_TAP_GAP = 300;
  const MAX_TAP_DISTANCE = 30;
  const DRAG_THRESHOLD = 5;
  const ZOOM_SENSITIVITY = 0.015;
  let map$5 = null;
  let enabled$1 = false;
  let disabledInteractions = [];
  let dragPanControl$1 = null;
  let state = "idle";
  let firstTapStartTimestamp = 0;
  let firstTapX = 0;
  let firstTapY = 0;
  let secondTapTimer = null;
  let initialY = 0;
  let initialResolution = 0;
  let interacting = false;
  const END_INTERACTION_DURATION = 200;
  function isDoubleClickZoom(interaction) {
    var _a, _b;
    const DoubleClickZoom = (_b = (_a = window.ol) == null ? void 0 : _a.interaction) == null ? void 0 : _b.DoubleClickZoom;
    return DoubleClickZoom !== void 0 && interaction instanceof DoubleClickZoom;
  }
  function resetGesture$1() {
    var _a, _b;
    state = "idle";
    dragPanControl$1 == null ? void 0 : dragPanControl$1.restore();
    if (secondTapTimer !== null) {
      clearTimeout(secondTapTimer);
      secondTapTimer = null;
    }
    if (interacting) {
      interacting = false;
      (_b = map$5 == null ? void 0 : (_a = map$5.getView()).endInteraction) == null ? void 0 : _b.call(_a, END_INTERACTION_DURATION);
    }
  }
  function distanceBetweenTaps(x, y) {
    return Math.sqrt((x - firstTapX) ** 2 + (y - firstTapY) ** 2);
  }
  function applyZoom(currentY) {
    if (!map$5) return;
    const view = map$5.getView();
    if (!view.setResolution) return;
    const deltaY = initialY - currentY;
    view.setResolution(initialResolution * Math.pow(2, -deltaY * ZOOM_SENSITIVITY));
  }
  function onTouchStart$1(event) {
    var _a, _b;
    if (event.targetTouches.length !== 1) {
      resetGesture$1();
      return;
    }
    if (!(event.target instanceof HTMLCanvasElement)) return;
    const touch = event.targetTouches[0];
    if (state === "idle") {
      state = "firstTapDown";
      firstTapStartTimestamp = event.timeStamp;
      firstTapX = touch.clientX;
      firstTapY = touch.clientY;
      return;
    }
    if (state === "waitingSecondTap") {
      if (distanceBetweenTaps(touch.clientX, touch.clientY) > MAX_TAP_DISTANCE) {
        resetGesture$1();
        state = "firstTapDown";
        firstTapStartTimestamp = event.timeStamp;
        firstTapX = touch.clientX;
        firstTapY = touch.clientY;
        return;
      }
      if (secondTapTimer !== null) {
        clearTimeout(secondTapTimer);
        secondTapTimer = null;
      }
      const view = map$5 == null ? void 0 : map$5.getView();
      const resolution = (_a = view == null ? void 0 : view.getResolution) == null ? void 0 : _a.call(view);
      if (resolution === void 0) {
        resetGesture$1();
        return;
      }
      state = "secondTapDown";
      initialY = touch.clientY;
      initialResolution = resolution;
      dragPanControl$1 == null ? void 0 : dragPanControl$1.disable();
      (_b = view == null ? void 0 : view.beginInteraction) == null ? void 0 : _b.call(view);
      interacting = true;
      event.preventDefault();
      return;
    }
    resetGesture$1();
    state = "firstTapDown";
    firstTapStartTimestamp = event.timeStamp;
    firstTapX = touch.clientX;
    firstTapY = touch.clientY;
  }
  function onTouchMove$1(event) {
    if (state === "firstTapDown") {
      resetGesture$1();
      return;
    }
    if (state === "secondTapDown") {
      const touch = event.targetTouches[0];
      if (Math.abs(touch.clientY - initialY) > DRAG_THRESHOLD) {
        state = "zooming";
        event.preventDefault();
        applyZoom(touch.clientY);
      }
      return;
    }
    if (state === "zooming") {
      event.preventDefault();
      const touch = event.targetTouches[0];
      applyZoom(touch.clientY);
    }
  }
  function onTouchEnd$1(event) {
    if (state === "firstTapDown") {
      const elapsed = event.timeStamp - firstTapStartTimestamp;
      if (elapsed < TAP_DURATION_THRESHOLD) {
        state = "waitingSecondTap";
        secondTapTimer = setTimeout(() => {
          resetGesture$1();
        }, MAX_TAP_GAP);
        return;
      }
      resetGesture$1();
      return;
    }
    resetGesture$1();
  }
  function onTouchStartCapture(event) {
    onTouchStart$1(event);
    if (state === "secondTapDown" || state === "zooming") {
      event.stopPropagation();
    }
  }
  function onTouchMoveCapture(event) {
    onTouchMove$1(event);
    if (state === "secondTapDown" || state === "zooming") {
      event.stopPropagation();
    }
  }
  function onTouchEndCapture(event) {
    const wasActive = state === "secondTapDown" || state === "zooming";
    onTouchEnd$1(event);
    if (wasActive) {
      event.stopPropagation();
    }
  }
  function addListeners$1() {
    document.addEventListener("touchstart", onTouchStartCapture, { capture: true, passive: false });
    document.addEventListener("touchmove", onTouchMoveCapture, { capture: true, passive: false });
    document.addEventListener("touchend", onTouchEndCapture, { capture: true });
  }
  function removeListeners$1() {
    document.removeEventListener("touchstart", onTouchStartCapture, { capture: true });
    document.removeEventListener("touchmove", onTouchMoveCapture, { capture: true });
    document.removeEventListener("touchend", onTouchEndCapture, { capture: true });
  }
  function disableDoubleClickZoomInteractions() {
    if (!map$5) return;
    const interactions = map$5.getInteractions().getArray();
    disabledInteractions = interactions.filter(isDoubleClickZoom);
    for (const interaction of disabledInteractions) {
      interaction.setActive(false);
    }
  }
  function restoreDoubleClickZoomInteractions() {
    for (const interaction of disabledInteractions) {
      interaction.setActive(true);
    }
    disabledInteractions = [];
  }
  const ngrsZoom = {
    id: MODULE_ID$f,
    name: {
      en: "Ngrs Zoom",
      ru: "Нгрс-зум"
    },
    description: {
      en: "Double-tap and drag up/down to zoom. Also disables the built-in double-tap zoom.",
      ru: "Двойной тап и перетаскивание вверх/вниз для зума. Заодно отключает стандартный зум по двойному тапу."
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    enable() {
      enabled$1 = true;
      addListeners$1();
      return getOlMap().then((olMap2) => {
        if (!enabled$1) return;
        map$5 = olMap2;
        if (dragPanControl$1 === null) {
          dragPanControl$1 = createDragPanControl(olMap2);
        }
        disableDoubleClickZoomInteractions();
      });
    },
    disable() {
      enabled$1 = false;
      removeListeners$1();
      restoreDoubleClickZoomInteractions();
      dragPanControl$1 == null ? void 0 : dragPanControl$1.restore();
      dragPanControl$1 = null;
      resetGesture$1();
    }
  };
  const MODULE_ID$e = "drawButtonFix";
  let observer$1 = null;
  const drawButtonFix = {
    id: MODULE_ID$e,
    name: { en: "Draw Button Fix", ru: "Фикс кнопки рисования" },
    description: {
      en: "Draw button is always enabled — fixes a game bug where the button gets stuck in disabled state",
      ru: "Кнопка «Рисовать» всегда активна — исправляет баг игры, когда кнопка зависает в неактивном состоянии"
    },
    defaultEnabled: true,
    category: "fix",
    init() {
    },
    enable() {
      observer$1 = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
          if (mutation.type === "attributes" && mutation.target instanceof Element && mutation.target.id === "draw") {
            mutation.target.removeAttribute("disabled");
          }
        }
      });
      observer$1.observe(document.body, {
        subtree: true,
        attributes: true,
        attributeFilter: ["disabled"]
      });
    },
    disable() {
      observer$1 == null ? void 0 : observer$1.disconnect();
      observer$1 = null;
    }
  };
  const MODULE_ID$d = "groupErrorToasts";
  const ERROR_TOAST_CLASS = "error-toast";
  let restorePatch = null;
  function getContainerIdentity(selector) {
    if (!selector) return "body";
    return selector.className || "unknown";
  }
  function getDeduplicationKey(text, selector) {
    return `${text}::${getContainerIdentity(selector)}`;
  }
  function removeToastElementImmediately(instance) {
    const element = instance.toastElement;
    if (!element) return;
    if (element.timeOutValue) {
      clearTimeout(element.timeOutValue);
    }
    element.remove();
  }
  function wrapCallback(toast, key, tracked) {
    const previousCallback = toast.options.callback;
    toast.options.callback = () => {
      var _a;
      if (((_a = tracked.get(key)) == null ? void 0 : _a.instance) === toast) {
        tracked.delete(key);
      }
      previousCallback == null ? void 0 : previousCallback();
    };
  }
  function installPatch(proto) {
    const tracked = /* @__PURE__ */ new Map();
    const original = proto.showToast;
    proto.showToast = function() {
      var _a, _b, _c;
      if (this.options.className !== ERROR_TOAST_CLASS) {
        original.call(this);
        return;
      }
      const text = this.options.text;
      const key = getDeduplicationKey(text, this.options.selector);
      const existing = tracked.get(key);
      if ((_a = existing == null ? void 0 : existing.instance.toastElement) == null ? void 0 : _a.parentNode) {
        const newCount = existing.count + 1;
        this.options.text = `${existing.originalText} (×${newCount})`;
        tracked.set(key, {
          instance: this,
          count: newCount,
          originalText: existing.originalText
        });
        removeToastElementImmediately(existing.instance);
        (_c = (_b = existing.instance.options).callback) == null ? void 0 : _c.call(_b);
      } else {
        tracked.set(key, { instance: this, count: 1, originalText: text });
      }
      wrapCallback(this, key, tracked);
      original.call(this);
    };
    return () => {
      proto.showToast = original;
      tracked.clear();
    };
  }
  const groupErrorToasts = {
    id: MODULE_ID$d,
    name: { en: "Group Error Toasts", ru: "Группировка тостов ошибок" },
    description: {
      en: "Groups identical error toasts into one with a counter instead of stacking",
      ru: "Группирует одинаковые тосты ошибок в один со счётчиком вместо накопления"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    enable() {
      restorePatch = installPatch(window.Toastify.prototype);
    },
    disable() {
      restorePatch == null ? void 0 : restorePatch();
      restorePatch = null;
    }
  };
  const styles$5 = "#attack-slider-close{display:none!important}";
  const MODULE_ID$c = "removeAttackCloseButton";
  const removeAttackCloseButton = {
    id: MODULE_ID$c,
    name: { en: "Remove Attack Close Button", ru: "Убрать кнопку «Закрыть» в атаке" },
    description: {
      en: "Removes the Close button in attack mode to avoid hitting it instead of Fire. Tap Attack again to exit",
      ru: "Убирает кнопку «Закрыть» в режиме атаки, чтобы не нажать её случайно вместо «Огонь!». Выход из режима — повторный клик по кнопке «Атака»"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    enable() {
      injectStyles(styles$5, MODULE_ID$c);
    },
    disable() {
      removeStyles(MODULE_ID$c);
    }
  };
  const MODULE_ID$b = "keepScreenOn";
  let wakeLock = null;
  async function requestWakeLock() {
    wakeLock = await navigator.wakeLock.request("screen");
    wakeLock.addEventListener("release", () => {
      wakeLock = null;
    });
  }
  function onVisibilityChange() {
    if (document.visibilityState === "visible" && wakeLock === null) {
      void requestWakeLock().catch(() => {
      });
    }
  }
  const keepScreenOn = {
    id: MODULE_ID$b,
    name: { en: "Keep Screen On", ru: "Экран не гаснет" },
    description: {
      en: "Keeps screen awake during gameplay (Wake Lock API)",
      ru: "Экран не гаснет во время игры (Wake Lock API)"
    },
    defaultEnabled: true,
    category: "feature",
    init() {
    },
    enable() {
      document.addEventListener("visibilitychange", onVisibilityChange);
      return requestWakeLock();
    },
    disable() {
      document.removeEventListener("visibilitychange", onVisibilityChange);
      const released = wakeLock == null ? void 0 : wakeLock.release();
      wakeLock = null;
      return released;
    }
  };
  const ITEM_TYPE_CORE = 1;
  const ITEM_TYPE_CATALYSER = 2;
  const ITEM_TYPE_REFERENCE = 3;
  const ITEM_TYPE_BROOM = 4;
  function isRecord$3(value) {
    return typeof value === "object" && value !== null;
  }
  function isInventoryCore(value) {
    return isRecord$3(value) && typeof value.g === "string" && value.t === ITEM_TYPE_CORE && typeof value.l === "number" && typeof value.a === "number";
  }
  function isInventoryCatalyser(value) {
    return isRecord$3(value) && typeof value.g === "string" && value.t === ITEM_TYPE_CATALYSER && typeof value.l === "number" && typeof value.a === "number";
  }
  function isInventoryReference(value) {
    return isRecord$3(value) && typeof value.g === "string" && value.t === ITEM_TYPE_REFERENCE && typeof value.l === "string" && typeof value.a === "number";
  }
  function isInventoryReferenceFull(value) {
    if (!isInventoryReference(value)) return false;
    const record = value;
    return Array.isArray(record.c) && record.c.length === 2 && typeof record.c[0] === "number" && typeof record.c[1] === "number" && typeof record.ti === "string";
  }
  function isInventoryBroom(value) {
    return isRecord$3(value) && typeof value.g === "string" && value.t === ITEM_TYPE_BROOM && typeof value.l === "number" && typeof value.a === "number";
  }
  function isInventoryItem(value) {
    return isInventoryCore(value) || isInventoryCatalyser(value) || isInventoryReference(value) || isInventoryBroom(value);
  }
  const INVENTORY_CACHE_KEY = "inventory-cache";
  function readInventoryCache() {
    const raw = localStorage.getItem(INVENTORY_CACHE_KEY);
    if (!raw) return [];
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return [];
    }
    if (!Array.isArray(parsed)) return [];
    return parsed;
  }
  function readInventoryReferences() {
    return readInventoryCache().filter(isInventoryReference);
  }
  function readFullInventoryReferences() {
    return readInventoryCache().filter(isInventoryReferenceFull);
  }
  function getCssVariable(name, fallback) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
  }
  function getTextColor() {
    return getCssVariable("--text", "#000000");
  }
  function getBackgroundColor() {
    return getCssVariable("--background", "#ffffff");
  }
  const MODULE_ID$a = "keyCountOnPoints";
  const MIN_ZOOM = 15;
  const DEBOUNCE_MS = 100;
  function buildRefCounts() {
    const refs = readInventoryReferences();
    const counts = /* @__PURE__ */ new Map();
    for (const ref of refs) {
      counts.set(ref.l, (counts.get(ref.l) ?? 0) + ref.a);
    }
    return counts;
  }
  let map$4 = null;
  let pointsSource$1 = null;
  let labelsSource = null;
  let labelsLayer = null;
  let debounceTimer = null;
  let mutationObserver = null;
  let onPointsChange = null;
  let onZoomChange = null;
  function renderLabels() {
    var _a, _b, _c, _d, _e, _f, _g;
    if (!labelsSource || !map$4 || !pointsSource$1) return;
    labelsSource.clear();
    const zoom = ((_b = (_a = map$4.getView()).getZoom) == null ? void 0 : _b.call(_a)) ?? 0;
    if (zoom < MIN_ZOOM) return;
    const refCounts = buildRefCounts();
    if (refCounts.size === 0) return;
    const ol = window.ol;
    const OlFeature = ol == null ? void 0 : ol.Feature;
    const OlPoint = (_c = ol == null ? void 0 : ol.geom) == null ? void 0 : _c.Point;
    const OlStyle = (_d = ol == null ? void 0 : ol.style) == null ? void 0 : _d.Style;
    const OlText = (_e = ol == null ? void 0 : ol.style) == null ? void 0 : _e.Text;
    const OlFill = (_f = ol == null ? void 0 : ol.style) == null ? void 0 : _f.Fill;
    const OlStroke = (_g = ol == null ? void 0 : ol.style) == null ? void 0 : _g.Stroke;
    if (!OlFeature || !OlPoint || !OlStyle || !OlText || !OlFill || !OlStroke) return;
    const textColor = getTextColor();
    const bgColor = getBackgroundColor();
    for (const feature of pointsSource$1.getFeatures()) {
      const id = feature.getId();
      if (typeof id !== "string") continue;
      const count = refCounts.get(id);
      if (!count || count <= 0) continue;
      const coords = feature.getGeometry().getCoordinates();
      const label = new OlFeature({ geometry: new OlPoint(coords) });
      label.setId(id + ":key-label");
      label.setStyle(
        new OlStyle({
          text: new OlText({
            font: "12px Manrope",
            text: String(count),
            fill: new OlFill({ color: textColor }),
            stroke: new OlStroke({ color: bgColor, width: 3 })
          }),
          zIndex: 5
        })
      );
      labelsSource.addFeature(label);
    }
  }
  function scheduleRender() {
    if (debounceTimer !== null) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(renderLabels, DEBOUNCE_MS);
  }
  const keyCountOnPoints = {
    id: MODULE_ID$a,
    name: { en: "Key count on points", ru: "Количество ключей на точках" },
    description: {
      en: "Shows the number of reference keys for each visible point on the map",
      ru: "Показывает число ключей (refs) для каждой видимой точки на карте"
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    enable() {
      return getOlMap().then((olMap2) => {
        var _a, _b, _c, _d;
        const ol = window.ol;
        const OlVectorSource = (_a = ol == null ? void 0 : ol.source) == null ? void 0 : _a.Vector;
        const OlVectorLayer = (_b = ol == null ? void 0 : ol.layer) == null ? void 0 : _b.Vector;
        if (!OlVectorSource || !OlVectorLayer) return;
        const pointsLayer = findLayerByName(olMap2, "points");
        if (!pointsLayer) return;
        const src = pointsLayer.getSource();
        if (!src) return;
        map$4 = olMap2;
        pointsSource$1 = src;
        labelsSource = new OlVectorSource();
        labelsLayer = new OlVectorLayer({
          // as unknown as: OL Vector constructor accepts a generic options bag;
          // IOlVectorSource cannot be narrowed to Record<string, unknown> without a guard
          source: labelsSource,
          zIndex: 5
        });
        olMap2.addLayer(labelsLayer);
        onPointsChange = scheduleRender;
        pointsSource$1.on("change", onPointsChange);
        onZoomChange = renderLabels;
        (_d = (_c = olMap2.getView()).on) == null ? void 0 : _d.call(_c, "change:resolution", onZoomChange);
        const invEl = document.getElementById("self-info__inv");
        if (invEl) {
          mutationObserver = new MutationObserver(renderLabels);
          mutationObserver.observe(invEl, { characterData: true, childList: true, subtree: true });
        }
        renderLabels();
      });
    },
    disable() {
      var _a, _b;
      if (debounceTimer !== null) {
        clearTimeout(debounceTimer);
        debounceTimer = null;
      }
      if (mutationObserver) {
        mutationObserver.disconnect();
        mutationObserver = null;
      }
      if (pointsSource$1 && onPointsChange) {
        pointsSource$1.un("change", onPointsChange);
        onPointsChange = null;
      }
      if (map$4 && onZoomChange) {
        (_b = (_a = map$4.getView()).un) == null ? void 0 : _b.call(_a, "change:resolution", onZoomChange);
        onZoomChange = null;
      }
      if (map$4 && labelsLayer) {
        map$4.removeLayer(labelsLayer);
      }
      map$4 = null;
      pointsSource$1 = null;
      labelsSource = null;
      labelsLayer = null;
    }
  };
  const MODULE_ID$9 = "largerPointTapArea";
  const HIT_TOLERANCE_PX = 15;
  let map$3 = null;
  let originalMethod = null;
  const largerPointTapArea = {
    id: MODULE_ID$9,
    name: { en: "Larger Point Tap Area", ru: "Увеличенная область нажатия" },
    description: {
      en: "Increases the tappable area of map points for easier selection on mobile",
      ru: "Увеличивает кликабельную область точек на карте для удобства на мобильных"
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    enable() {
      return getOlMap().then((olMap2) => {
        if (originalMethod || !olMap2.forEachFeatureAtPixel) return;
        map$3 = olMap2;
        originalMethod = olMap2.forEachFeatureAtPixel.bind(olMap2);
        const saved = originalMethod;
        olMap2.forEachFeatureAtPixel = (pixel, callback, options) => {
          saved(pixel, callback, {
            ...options,
            hitTolerance: HIT_TOLERANCE_PX
          });
        };
      });
    },
    disable() {
      if (map$3 && originalMethod && map$3.forEachFeatureAtPixel) {
        map$3.forEachFeatureAtPixel = originalMethod;
      }
      originalMethod = null;
      map$3 = null;
    }
  };
  const styles$4 = ".info.popup .i-buttons .svp-next-point-button{position:fixed;bottom:5px;right:5px;width:32px;height:32px;min-height:auto}";
  const MODULE_ID$8 = "nextPointNavigation";
  const BUTTON_CLASS$1 = "svp-next-point-button";
  const INTERACTION_RANGE = 45;
  const AUTOZOOM_THRESHOLD = 16;
  const AUTOZOOM_TARGET = 17;
  const AUTOZOOM_TIMEOUT_MS = 3e3;
  let map$2 = null;
  let pointsSource = null;
  let playerSource = null;
  const rangeVisited = /* @__PURE__ */ new Set();
  let expectedNextGuid = null;
  let lastSeenGuid = null;
  let fakeClickRetries = 0;
  const MAX_FAKE_CLICK_RETRIES = 3;
  let popupObserver$3 = null;
  let playerMoveHandler = null;
  let autozoomInProgress = false;
  let onRangeButtonClick = null;
  let sourceChangeHandler = null;
  function getGeodeticDistance(coordsA, coordsB) {
    var _a, _b;
    const ol = window.ol;
    if (!((_a = ol == null ? void 0 : ol.geom) == null ? void 0 : _a.LineString) || !((_b = ol.sphere) == null ? void 0 : _b.getLength)) return Infinity;
    const line = new ol.geom.LineString([coordsA, coordsB]);
    return ol.sphere.getLength(line);
  }
  function findFeaturesInRange(center, features, radiusMeters) {
    const result = [];
    for (const feature of features) {
      const id = feature.getId();
      if (id === void 0) continue;
      const coords = feature.getGeometry().getCoordinates();
      if (getGeodeticDistance(center, coords) <= radiusMeters) {
        result.push(feature);
      }
    }
    return result;
  }
  function findNearestByDistance(center, features) {
    let nearest = null;
    let minDistanceSquared = Infinity;
    for (const feature of features) {
      const coords = feature.getGeometry().getCoordinates();
      const dx = coords[0] - center[0];
      const dy = coords[1] - center[1];
      const distanceSquared = dx * dx + dy * dy;
      if (distanceSquared < minDistanceSquared) {
        minDistanceSquared = distanceSquared;
        nearest = feature;
      }
    }
    return nearest;
  }
  function hasFreeSlots(feature) {
    var _a;
    const cores = (_a = feature.get) == null ? void 0 : _a.call(feature, "cores");
    return cores === void 0 || typeof cores === "number" && cores < 6;
  }
  function isDiscoverable(feature) {
    const id = feature.getId();
    if (id === void 0) return false;
    const cooldowns = JSON.parse(localStorage.getItem("cooldowns") ?? "{}");
    const cooldown = cooldowns[String(id)];
    if (!(cooldown == null ? void 0 : cooldown.t)) return true;
    return cooldown.t <= Date.now() && (cooldown.c ?? 0) > 0;
  }
  function findNextByPriority(center, candidates) {
    return findNearestByDistance(center, candidates.filter(hasFreeSlots)) ?? findNearestByDistance(center, candidates.filter(isDiscoverable)) ?? findNearestByDistance(center, candidates);
  }
  function getPlayerCoordinates() {
    if (!playerSource) return null;
    const features = playerSource.getFeatures();
    if (features.length === 0) return null;
    return features[0].getGeometry().getCoordinates();
  }
  function getPopupPointId() {
    const popup2 = document.querySelector(".info.popup");
    if (!popup2 || popup2.classList.contains("hidden")) return null;
    return popup2.dataset.guid ?? null;
  }
  function findFeatureById(id) {
    if (!pointsSource) return null;
    for (const feature of pointsSource.getFeatures()) {
      if (feature.getId() === id) return feature;
    }
    return null;
  }
  function openPointPopup(guid) {
    var _a;
    expectedNextGuid = guid;
    if (typeof window.showInfo === "function") {
      (_a = document.querySelector(".info.popup")) == null ? void 0 : _a.classList.add("hidden");
      window.showInfo(guid);
      return;
    }
    if (!map$2 || typeof map$2.dispatchEvent !== "function" || typeof map$2.getPixelFromCoordinate !== "function") {
      return;
    }
    const feature = findFeatureById(guid);
    if (!feature) return;
    const coords = feature.getGeometry().getCoordinates();
    const pixel = map$2.getPixelFromCoordinate(coords);
    map$2.dispatchEvent({ type: "click", pixel, originalEvent: {} });
  }
  function tryNavigateInRange() {
    if (!map$2 || !pointsSource) return false;
    const currentId = getPopupPointId();
    if (!currentId) return false;
    const playerCoordinates = getPlayerCoordinates();
    if (!playerCoordinates) return false;
    rangeVisited.add(currentId);
    const features = pointsSource.getFeatures();
    const inRange = findFeaturesInRange(playerCoordinates, features, INTERACTION_RANGE);
    const candidates = inRange.filter((feature) => {
      const id = feature.getId();
      return id !== void 0 && !rangeVisited.has(id);
    });
    let next = findNextByPriority(playerCoordinates, candidates);
    if (!next) {
      rangeVisited.clear();
      rangeVisited.add(currentId);
      const cycledCandidates = inRange.filter((feature) => {
        const id = feature.getId();
        return id !== void 0 && !rangeVisited.has(id);
      });
      next = findNextByPriority(playerCoordinates, cycledCandidates);
    }
    if (!next) return false;
    const nextId = next.getId();
    if (nextId === void 0) return false;
    openPointPopup(String(nextId));
    return true;
  }
  function autozoomAndNavigate() {
    var _a, _b;
    if (!map$2 || !pointsSource) return;
    const view = map$2.getView();
    const currentZoom = (_a = view.getZoom) == null ? void 0 : _a.call(view);
    if (currentZoom === void 0 || currentZoom >= AUTOZOOM_THRESHOLD) return;
    const playerCoordinates = getPlayerCoordinates();
    if (!playerCoordinates) return;
    autozoomInProgress = true;
    const savedCenter = view.getCenter();
    const savedZoom = currentZoom;
    view.setCenter(playerCoordinates);
    (_b = view.setZoom) == null ? void 0 : _b.call(view, AUTOZOOM_TARGET);
    let resolved = false;
    const finish = () => {
      var _a2;
      if (resolved) return;
      resolved = true;
      autozoomInProgress = false;
      pointsSource == null ? void 0 : pointsSource.un("change", onSourceChange);
      view.setCenter(savedCenter);
      (_a2 = view.setZoom) == null ? void 0 : _a2.call(view, savedZoom);
      updateButtonStates();
    };
    const onSourceChange = () => {
      finish();
    };
    pointsSource.on("change", onSourceChange);
    setTimeout(finish, AUTOZOOM_TIMEOUT_MS);
  }
  function navigateInRange() {
    tryNavigateInRange();
  }
  function hasInRangePoints(excludePointId) {
    if (!pointsSource) return false;
    const playerCoordinates = getPlayerCoordinates();
    if (!playerCoordinates) return false;
    const features = pointsSource.getFeatures();
    const inRange = findFeaturesInRange(playerCoordinates, features, INTERACTION_RANGE);
    return inRange.some((feature) => feature.getId() !== excludePointId);
  }
  function injectButton(popup2) {
    const buttonsContainer = popup2.querySelector(".i-buttons");
    if (!buttonsContainer) return;
    if (!popup2.querySelector(`.${BUTTON_CLASS$1}`)) {
      const rangeButton2 = document.createElement("button");
      rangeButton2.className = BUTTON_CLASS$1;
      rangeButton2.textContent = "→";
      rangeButton2.title = "Следующая точка в радиусе взаимодействия";
      onRangeButtonClick = () => {
        navigateInRange();
      };
      rangeButton2.addEventListener("click", (event) => {
        event.stopPropagation();
        onRangeButtonClick == null ? void 0 : onRangeButtonClick();
      });
      buttonsContainer.appendChild(rangeButton2);
    }
    const inRange = hasInRangePoints(getPopupPointId());
    const rangeButton = popup2.querySelector(`.${BUTTON_CLASS$1}`);
    if (rangeButton) {
      rangeButton.disabled = !inRange;
    }
    if (!inRange && !autozoomInProgress) {
      autozoomAndNavigate();
    }
  }
  function updateButtonStates() {
    const popup2 = document.querySelector(".info.popup");
    if (!popup2 || popup2.classList.contains("hidden")) return;
    const rangeButton = popup2.querySelector(`.${BUTTON_CLASS$1}`);
    if (rangeButton) {
      rangeButton.disabled = !hasInRangePoints(getPopupPointId());
    }
  }
  function removeButton$1() {
    var _a;
    (_a = document.querySelector(`.${BUTTON_CLASS$1}`)) == null ? void 0 : _a.remove();
    onRangeButtonClick = null;
  }
  function onPopupMutation(popup2) {
    const isVisible = !popup2.classList.contains("hidden");
    if (isVisible) {
      const currentGuid = popup2.dataset.guid ?? null;
      if (expectedNextGuid !== null) {
        if (currentGuid === lastSeenGuid && fakeClickRetries < MAX_FAKE_CLICK_RETRIES) {
          fakeClickRetries++;
          expectedNextGuid = null;
          navigateInRange();
          return;
        }
        if (currentGuid === lastSeenGuid && expectedNextGuid) {
          rangeVisited.add(expectedNextGuid);
        }
        fakeClickRetries = 0;
        if (currentGuid !== expectedNextGuid && currentGuid) {
          rangeVisited.add(currentGuid);
        }
        expectedNextGuid = null;
      } else if (currentGuid !== lastSeenGuid) {
        fakeClickRetries = 0;
        rangeVisited.clear();
      }
      lastSeenGuid = currentGuid;
      injectButton(popup2);
    }
  }
  function startObservingPopup(popup2) {
    popupObserver$3 = new MutationObserver(() => {
      onPopupMutation(popup2);
    });
    popupObserver$3.observe(popup2, {
      attributes: true,
      attributeFilter: ["class", "data-guid"],
      childList: true,
      subtree: true
    });
    if (!popup2.classList.contains("hidden")) {
      injectButton(popup2);
    }
  }
  function observePopup() {
    const popup2 = document.querySelector(".info.popup");
    if (popup2) {
      startObservingPopup(popup2);
      return;
    }
    void waitForElement(".info.popup").then((element) => {
      if (!map$2) return;
      startObservingPopup(element);
    });
  }
  const nextPointNavigation = {
    id: MODULE_ID$8,
    name: { en: "Next point navigation", ru: "Переход к следующей точке" },
    description: {
      en: "Cycle through points in interaction range",
      ru: "Зацикленная навигация по точкам в радиусе взаимодействия"
    },
    defaultEnabled: true,
    category: "feature",
    init() {
    },
    enable() {
      return getOlMap().then((olMap2) => {
        const pointsLayer = findLayerByName(olMap2, "points");
        if (!pointsLayer) return;
        const source = pointsLayer.getSource();
        if (!source) return;
        const playerLayer = findLayerByName(olMap2, "player");
        const playerLayerSource = (playerLayer == null ? void 0 : playerLayer.getSource()) ?? null;
        map$2 = olMap2;
        pointsSource = source;
        playerSource = playerLayerSource;
        injectStyles(styles$4, MODULE_ID$8);
        observePopup();
        sourceChangeHandler = () => {
          updateButtonStates();
        };
        pointsSource.on("change", sourceChangeHandler);
        const infoElement = document.querySelector(".info");
        if (infoElement) {
          playerMoveHandler = () => {
            updateButtonStates();
          };
          infoElement.addEventListener("playermove", playerMoveHandler);
        }
      });
    },
    disable() {
      var _a;
      if (popupObserver$3) {
        popupObserver$3.disconnect();
        popupObserver$3 = null;
      }
      if (playerMoveHandler) {
        (_a = document.querySelector(".info")) == null ? void 0 : _a.removeEventListener("playermove", playerMoveHandler);
        playerMoveHandler = null;
      }
      if (pointsSource && sourceChangeHandler) {
        pointsSource.un("change", sourceChangeHandler);
        sourceChangeHandler = null;
      }
      removeButton$1();
      removeStyles(MODULE_ID$8);
      map$2 = null;
      pointsSource = null;
      playerSource = null;
      rangeVisited.clear();
      expectedNextGuid = null;
      lastSeenGuid = null;
      fakeClickRetries = 0;
      autozoomInProgress = false;
    }
  };
  const css = ".svp-refs-on-map-button{background:none;border:1px solid var(--border-transp);color:var(--text);padding:4px 8px;font-size:14px;cursor:pointer}.svp-refs-on-map-close{position:fixed;bottom:8px;left:50%;transform:translate(-50%);z-index:1;font-size:1.5em;padding:0 .1em;align-self:center}.svp-refs-on-map-trash{position:fixed;bottom:100px;right:20px;z-index:10;background:var(--background-transp);border:1px solid var(--border-transp);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);color:var(--text);font-size:14px;padding:8px 12px;border-radius:8px;cursor:pointer;min-width:48px;text-align:center}";
  const MODULE_ID$7 = "refsOnMap";
  const REFS_TAB_INDEX = "3";
  const GAME_LAYER_NAMES = ["points", "lines", "regions"];
  const TEAM_BATCH_SIZE = 5;
  const TEAM_BATCH_DELAY_MS = 100;
  const AMOUNT_ZOOM = 15;
  const TITLE_ZOOM = 17;
  const TITLE_MAX_LENGTH = 12;
  const SELECTED_COLOR = "#BB7100";
  const NEUTRAL_COLOR = "#666666";
  const INVENTORY_API = "/api/inventory";
  const REFS_TAB_TYPE = 3;
  const COLLAPSIBLE_TOGGLE_ID = "svp-top-toggle";
  const COLLAPSIBLE_EXPAND_ID = "svp-top-expand";
  function isPointApiResponse(value) {
    return typeof value === "object" && value !== null;
  }
  async function fetchPointTeam$1(pointGuid) {
    var _a;
    try {
      const response = await fetch(`/api/point?guid=${pointGuid}&status=1`);
      const json = await response.json();
      if (isPointApiResponse(json) && typeof ((_a = json.data) == null ? void 0 : _a.te) === "number") {
        return json.data.te;
      }
    } catch {
    }
    return null;
  }
  function delay(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
  }
  function isDeleteApiResponse(value) {
    return typeof value === "object" && value !== null;
  }
  async function deleteRefsFromServer(items) {
    const response = await fetch(INVENTORY_API, {
      method: "DELETE",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ selection: items, tab: REFS_TAB_TYPE })
    });
    const json = await response.json();
    if (isDeleteApiResponse(json)) return json;
    return {};
  }
  function removeRefsFromCache(deletedGuids) {
    const raw = localStorage.getItem(INVENTORY_CACHE_KEY);
    if (!raw) return;
    let items;
    try {
      items = JSON.parse(raw);
    } catch {
      return;
    }
    if (!Array.isArray(items)) return;
    const filtered = items.filter((item) => {
      if (typeof item !== "object" || item === null) return true;
      const record = item;
      if (record.t !== REFS_TAB_TYPE) return true;
      return typeof record.g === "string" && !deletedGuids.has(record.g);
    });
    localStorage.setItem(INVENTORY_CACHE_KEY, JSON.stringify(filtered));
  }
  function updateInventoryCounter(total) {
    const counter = document.getElementById("self-info__inv");
    if (counter) counter.textContent = String(total);
  }
  let olMap = null;
  let refsSource = null;
  let refsLayer = null;
  let showButton = null;
  let closeButton = null;
  let trashButton = null;
  let tabClickHandler = null;
  let mapClickHandler = null;
  let viewerOpen = false;
  let beforeOpenZoom;
  let beforeOpenRotation;
  let beforeOpenFollow = null;
  const teamCache = /* @__PURE__ */ new Map();
  let teamLoadAborted = false;
  let overallRefsToDelete = 0;
  let uniqueRefsToDelete = 0;
  let ngrsZoomDisabledByViewer = false;
  function expandHexColor(color) {
    const match = /^#([0-9a-fA-F])([0-9a-fA-F])([0-9a-fA-F])$/.exec(color);
    if (match) return `#${match[1]}${match[1]}${match[2]}${match[2]}${match[3]}${match[3]}`;
    return color;
  }
  function getTeamColor(team) {
    if (team === void 0) return NEUTRAL_COLOR;
    const property = `--team-${team}`;
    const raw = getComputedStyle(document.documentElement).getPropertyValue(property).trim();
    return raw ? expandHexColor(raw) : NEUTRAL_COLOR;
  }
  function createLayerStyleFunction() {
    return (feature) => {
      var _a, _b, _c, _d;
      const olStyle = (_a = window.ol) == null ? void 0 : _a.style;
      if (!(olStyle == null ? void 0 : olStyle.Style) || !olStyle.Text || !olStyle.Fill || !olStyle.Stroke || !olStyle.Circle) {
        return [];
      }
      const {
        Style: OlStyle,
        Text: OlText,
        Fill: OlFill,
        Stroke: OlStroke,
        Circle: OlCircle
      } = olStyle;
      const properties = ((_b = feature.getProperties) == null ? void 0 : _b.call(feature)) ?? {};
      const amount = typeof properties.amount === "number" ? properties.amount : 0;
      const title = typeof properties.title === "string" ? properties.title : "";
      const team = typeof properties.team === "number" ? properties.team : void 0;
      const isSelected = properties.isSelected === true;
      const zoom = ((_d = olMap == null ? void 0 : (_c = olMap.getView()).getZoom) == null ? void 0 : _d.call(_c)) ?? 0;
      const teamColor = getTeamColor(team);
      const baseRadius = zoom >= 16 ? 10 : 8;
      const radius = isSelected ? baseRadius * 1.4 : baseRadius;
      const fillColor = isSelected ? SELECTED_COLOR : teamColor + "40";
      const strokeColor = isSelected ? SELECTED_COLOR : teamColor;
      const strokeWidth = isSelected ? 4 : 3;
      const textColor = getTextColor();
      const backgroundColor = getBackgroundColor();
      const styles2 = [
        new OlStyle({
          image: new OlCircle({
            radius,
            fill: new OlFill({ color: fillColor }),
            stroke: new OlStroke({ color: strokeColor, width: strokeWidth })
          }),
          zIndex: isSelected ? 3 : 1
        })
      ];
      if (zoom >= AMOUNT_ZOOM) {
        styles2.push(
          new OlStyle({
            text: new OlText({
              font: `${zoom >= 15 ? 14 : 12}px Manrope`,
              text: String(amount),
              fill: new OlFill({ color: textColor }),
              stroke: new OlStroke({ color: backgroundColor, width: 3 })
            }),
            zIndex: 2
          })
        );
      }
      if (zoom >= TITLE_ZOOM) {
        const displayTitle = title.length <= TITLE_MAX_LENGTH ? title : title.slice(0, TITLE_MAX_LENGTH - 2).trim() + "…";
        styles2.push(
          new OlStyle({
            text: new OlText({
              font: "12px Manrope",
              text: displayTitle,
              fill: new OlFill({ color: textColor }),
              stroke: new OlStroke({ color: backgroundColor, width: 3 }),
              offsetY: 18,
              textBaseline: "top"
            }),
            zIndex: 2
          })
        );
      }
      return styles2;
    };
  }
  function updateTrashCounter() {
    if (!trashButton) return;
    trashButton.textContent = uniqueRefsToDelete > 0 ? `🗑️ ${uniqueRefsToDelete} (${overallRefsToDelete})` : "";
    trashButton.style.visibility = uniqueRefsToDelete > 0 ? "visible" : "hidden";
  }
  function toggleFeatureSelection(feature) {
    var _a, _b;
    const properties = ((_a = feature.getProperties) == null ? void 0 : _a.call(feature)) ?? {};
    const isSelected = properties.isSelected === true;
    const amount = typeof properties.amount === "number" ? properties.amount : 0;
    (_b = feature.set) == null ? void 0 : _b.call(feature, "isSelected", !isSelected);
    overallRefsToDelete += amount * (isSelected ? -1 : 1);
    uniqueRefsToDelete += isSelected ? -1 : 1;
    updateTrashCounter();
  }
  function handleMapClick(event) {
    if (!(olMap == null ? void 0 : olMap.forEachFeatureAtPixel)) return;
    olMap.forEachFeatureAtPixel(
      event.pixel,
      (feature) => {
        toggleFeatureSelection(feature);
      },
      {
        layerFilter: (layer) => layer.get("name") === "svp-refs-on-map"
      }
    );
  }
  async function handleDeleteClick() {
    var _a, _b, _c;
    if (uniqueRefsToDelete === 0 || !refsSource) return;
    const message = t({
      en: `Delete ${overallRefsToDelete} ref(s) from ${uniqueRefsToDelete} point(s)?`,
      ru: `Удалить ${overallRefsToDelete} ключ(ей) от ${uniqueRefsToDelete} точ(ек)?`
    });
    if (!confirm(message)) return;
    const selectedFeatures = refsSource.getFeatures().filter((feature) => {
      var _a2;
      const properties = (_a2 = feature.getProperties) == null ? void 0 : _a2.call(feature);
      return properties !== void 0 && properties.isSelected === true;
    });
    const items = {};
    const deletedGuids = /* @__PURE__ */ new Set();
    for (const feature of selectedFeatures) {
      const id = feature.getId();
      const properties = (_a = feature.getProperties) == null ? void 0 : _a.call(feature);
      const amount = properties == null ? void 0 : properties.amount;
      if (typeof id === "string" && typeof amount === "number") {
        items[id] = amount;
        deletedGuids.add(id);
      }
    }
    try {
      const response = await deleteRefsFromServer(items);
      if (response.error) {
        console.error(`[SVP] ${MODULE_ID$7}: deletion error:`, response.error);
        return;
      }
      for (const feature of selectedFeatures) {
        (_b = refsSource.removeFeature) == null ? void 0 : _b.call(refsSource, feature);
      }
      removeRefsFromCache(deletedGuids);
      if (typeof ((_c = response.count) == null ? void 0 : _c.total) === "number") {
        updateInventoryCounter(response.count.total);
      }
      overallRefsToDelete = 0;
      uniqueRefsToDelete = 0;
      updateTrashCounter();
    } catch (error) {
      console.error(`[SVP] ${MODULE_ID$7}: deletion failed:`, error);
    }
  }
  async function loadTeamDataForRefs(refs) {
    var _a, _b;
    const pointGuids = /* @__PURE__ */ new Set();
    for (const ref of refs) {
      if (!teamCache.has(ref.l)) {
        pointGuids.add(ref.l);
      }
    }
    const uncachedGuids = Array.from(pointGuids);
    teamLoadAborted = false;
    for (let i = 0; i < uncachedGuids.length; i += TEAM_BATCH_SIZE) {
      if (teamLoadAborted) return;
      const batch = uncachedGuids.slice(i, i + TEAM_BATCH_SIZE);
      const results = await Promise.all(
        batch.map(async (pointGuid) => {
          const team = await fetchPointTeam$1(pointGuid);
          return { pointGuid, team };
        })
      );
      for (const { pointGuid, team } of results) {
        if (team !== null) {
          teamCache.set(pointGuid, team);
          if (refsSource) {
            for (const feature of refsSource.getFeatures()) {
              const properties = ((_a = feature.getProperties) == null ? void 0 : _a.call(feature)) ?? {};
              if (properties.pointGuid === pointGuid) {
                (_b = feature.set) == null ? void 0 : _b.call(feature, "team", team);
              }
            }
          }
        }
      }
      if (i + TEAM_BATCH_SIZE < uncachedGuids.length) {
        await delay(TEAM_BATCH_DELAY_MS);
      }
    }
  }
  function setGameLayersVisible(visible) {
    var _a;
    if (!olMap) return;
    for (const layer of olMap.getLayers().getArray()) {
      const name = layer.get("name");
      if (typeof name === "string" && GAME_LAYER_NAMES.some((n) => name.startsWith(n))) {
        (_a = layer.setVisible) == null ? void 0 : _a.call(layer, visible);
      }
    }
  }
  function disableFollowMode() {
    localStorage.setItem("follow", "false");
    const checkbox2 = document.querySelector("#toggle-follow");
    if (checkbox2 instanceof HTMLInputElement) checkbox2.checked = false;
  }
  function restoreFollowMode() {
    if (beforeOpenFollow === null || beforeOpenFollow === "false") return;
    localStorage.setItem("follow", beforeOpenFollow);
    const checkbox2 = document.querySelector("#toggle-follow");
    if (checkbox2 instanceof HTMLInputElement) checkbox2.checked = true;
    beforeOpenFollow = null;
  }
  function hideGameUi() {
    const inventory = $(".inventory");
    if (inventory instanceof HTMLElement) inventory.classList.add("hidden");
    const bottomContainer = $(".bottom-container");
    if (bottomContainer instanceof HTMLElement) bottomContainer.style.display = "none";
    const topLeft = $(".topleft-container");
    if (topLeft instanceof HTMLElement) topLeft.style.display = "none";
    const toggle = document.getElementById(COLLAPSIBLE_TOGGLE_ID);
    if (toggle instanceof HTMLElement) toggle.style.display = "none";
    const expand = document.getElementById(COLLAPSIBLE_EXPAND_ID);
    if (expand instanceof HTMLElement) expand.style.display = "none";
    const layers = document.getElementById("layers");
    if (layers instanceof HTMLElement) layers.style.display = "none";
  }
  function restoreGameUi() {
    const bottomContainer = $(".bottom-container");
    if (bottomContainer instanceof HTMLElement) bottomContainer.style.display = "";
    const topLeft = $(".topleft-container");
    if (topLeft instanceof HTMLElement) topLeft.style.display = "";
    const toggle = document.getElementById(COLLAPSIBLE_TOGGLE_ID);
    if (toggle instanceof HTMLElement) toggle.style.display = "";
    const expand = document.getElementById(COLLAPSIBLE_EXPAND_ID);
    if (expand instanceof HTMLElement) expand.style.display = "";
    const layers = document.getElementById("layers");
    if (layers instanceof HTMLElement) layers.style.display = "";
  }
  function showViewer() {
    var _a, _b, _c, _d, _e, _f, _g, _h;
    if (viewerOpen || !olMap || !refsSource) return;
    const refs = readFullInventoryReferences();
    if (refs.length === 0) return;
    const ol = window.ol;
    const OlFeature = ol == null ? void 0 : ol.Feature;
    const OlPoint = (_a = ol == null ? void 0 : ol.geom) == null ? void 0 : _a.Point;
    const olProj = ol == null ? void 0 : ol.proj;
    if (!OlFeature || !OlPoint || !(olProj == null ? void 0 : olProj.fromLonLat)) return;
    viewerOpen = true;
    const view = olMap.getView();
    beforeOpenZoom = (_b = view.getZoom) == null ? void 0 : _b.call(view);
    beforeOpenRotation = view.getRotation();
    beforeOpenFollow = localStorage.getItem("follow");
    disableFollowMode();
    view.setRotation(0);
    hideGameUi();
    setGameLayersVisible(false);
    const ngrsZoomModule = getModuleById("ngrsZoom");
    const settings = loadSettings();
    if (ngrsZoomModule && isModuleEnabled(settings, ngrsZoomModule.id, ngrsZoomModule.defaultEnabled)) {
      void ngrsZoomModule.disable();
      ngrsZoomDisabledByViewer = true;
    }
    for (const ref of refs) {
      const mapCoords = olProj.fromLonLat(ref.c);
      const feature = new OlFeature({ geometry: new OlPoint(mapCoords) });
      feature.setId(ref.g);
      (_c = feature.set) == null ? void 0 : _c.call(feature, "amount", ref.a);
      (_d = feature.set) == null ? void 0 : _d.call(feature, "title", ref.ti);
      (_e = feature.set) == null ? void 0 : _e.call(feature, "pointGuid", ref.l);
      (_f = feature.set) == null ? void 0 : _f.call(feature, "isSelected", false);
      const cachedTeam = teamCache.get(ref.l);
      if (cachedTeam !== void 0) {
        (_g = feature.set) == null ? void 0 : _g.call(feature, "team", cachedTeam);
      }
      refsSource.addFeature(feature);
    }
    if (closeButton) closeButton.style.display = "";
    if (trashButton) {
      trashButton.style.visibility = "hidden";
      trashButton.style.display = "";
    }
    mapClickHandler = handleMapClick;
    (_h = olMap.on) == null ? void 0 : _h.call(olMap, "click", mapClickHandler);
    void loadTeamDataForRefs(refs);
  }
  function hideViewer() {
    var _a, _b;
    if (!viewerOpen) return;
    viewerOpen = false;
    teamLoadAborted = true;
    if (olMap && mapClickHandler) {
      (_a = olMap.un) == null ? void 0 : _a.call(olMap, "click", mapClickHandler);
      mapClickHandler = null;
    }
    refsSource == null ? void 0 : refsSource.clear();
    overallRefsToDelete = 0;
    uniqueRefsToDelete = 0;
    updateTrashCounter();
    setGameLayersVisible(true);
    restoreGameUi();
    if (closeButton) closeButton.style.display = "none";
    if (trashButton) trashButton.style.display = "none";
    const view = olMap == null ? void 0 : olMap.getView();
    if (view) {
      if (beforeOpenZoom !== void 0) {
        (_b = view.setZoom) == null ? void 0 : _b.call(view, beforeOpenZoom);
        beforeOpenZoom = void 0;
      }
      if (beforeOpenRotation !== void 0) {
        view.setRotation(beforeOpenRotation);
        beforeOpenRotation = void 0;
      }
    }
    restoreFollowMode();
    if (ngrsZoomDisabledByViewer) {
      const ngrsZoomModule = getModuleById("ngrsZoom");
      if (ngrsZoomModule) void ngrsZoomModule.enable();
      ngrsZoomDisabledByViewer = false;
    }
  }
  function updateButtonVisibility() {
    if (!showButton) return;
    const activeTab = $(".inventory__tab.active");
    const tabIndex = activeTab instanceof HTMLElement ? activeTab.dataset.tab : null;
    showButton.style.display = tabIndex === REFS_TAB_INDEX ? "" : "none";
  }
  const refsOnMap = {
    id: MODULE_ID$7,
    name: { en: "Refs on map", ru: "Ключи на карте" },
    description: {
      en: "View and manage points with collected keys on the map at any zoom level",
      ru: "Просмотр и управление точками с ключами на карте на любом масштабе"
    },
    defaultEnabled: true,
    category: "feature",
    init() {
    },
    enable() {
      injectStyles(css, MODULE_ID$7);
      return getOlMap().then(
        (map2) => {
          var _a, _b;
          try {
            const ol = window.ol;
            const OlVectorSource = (_a = ol == null ? void 0 : ol.source) == null ? void 0 : _a.Vector;
            const OlVectorLayer = (_b = ol == null ? void 0 : ol.layer) == null ? void 0 : _b.Vector;
            if (!OlVectorSource || !OlVectorLayer) return;
            olMap = map2;
            refsSource = new OlVectorSource();
            refsLayer = new OlVectorLayer({
              // as unknown as: OL Vector constructor accepts a generic options bag;
              // IOlVectorSource cannot be narrowed to Record<string, unknown> without a guard
              source: refsSource,
              name: "svp-refs-on-map",
              zIndex: 8,
              minZoom: 0,
              style: createLayerStyleFunction()
            });
            map2.addLayer(refsLayer);
            showButton = document.createElement("button");
            showButton.className = "svp-refs-on-map-button";
            showButton.textContent = t({ en: "On map", ru: "На карте" });
            showButton.addEventListener("click", showViewer);
            showButton.style.display = "none";
            const inventoryDelete = $("#inventory-delete");
            if (inventoryDelete == null ? void 0 : inventoryDelete.parentElement) {
              inventoryDelete.parentElement.insertBefore(showButton, inventoryDelete);
            }
            tabClickHandler = () => {
              updateButtonVisibility();
            };
            const tabContainer = $(".inventory__tabs");
            if (tabContainer) {
              tabContainer.addEventListener("click", tabClickHandler);
            }
            updateButtonVisibility();
            closeButton = document.createElement("button");
            closeButton.className = "svp-refs-on-map-close";
            closeButton.textContent = "[x]";
            closeButton.style.display = "none";
            closeButton.addEventListener("click", hideViewer);
            document.body.appendChild(closeButton);
            trashButton = document.createElement("button");
            trashButton.className = "svp-refs-on-map-trash";
            trashButton.style.display = "none";
            trashButton.addEventListener("click", () => {
              void handleDeleteClick();
            });
            document.body.appendChild(trashButton);
          } catch (error) {
            cleanupEnableSideEffects();
            throw error;
          }
        },
        (error) => {
          removeStyles(MODULE_ID$7);
          throw error;
        }
      );
    },
    disable() {
      cleanupEnableSideEffects();
    }
  };
  function cleanupEnableSideEffects() {
    if (viewerOpen) hideViewer();
    teamLoadAborted = true;
    if (olMap && refsLayer) {
      olMap.removeLayer(refsLayer);
    }
    if (showButton) {
      showButton.removeEventListener("click", showViewer);
      showButton.remove();
      showButton = null;
    }
    if (closeButton) {
      closeButton.removeEventListener("click", hideViewer);
      closeButton.remove();
      closeButton = null;
    }
    if (trashButton) {
      trashButton.remove();
      trashButton = null;
    }
    if (tabClickHandler) {
      const tabContainer = $(".inventory__tabs");
      if (tabContainer) {
        tabContainer.removeEventListener("click", tabClickHandler);
      }
      tabClickHandler = null;
    }
    removeStyles(MODULE_ID$7);
    teamCache.clear();
    olMap = null;
    refsSource = null;
    refsLayer = null;
  }
  const MODULE_ID$6 = "repairAtFullCharge";
  let observer = null;
  function extractTeamFromStyle(element) {
    const style = (element == null ? void 0 : element.getAttribute("style")) ?? "";
    const match = style.match(/--team-(\d+)/);
    return match ? Number(match[1]) : null;
  }
  function isSameTeam() {
    const playerTeam = extractTeamFromStyle(document.getElementById("self-info__name"));
    const pointTeam = extractTeamFromStyle(document.getElementById("i-stat__owner"));
    return playerTeam !== null && pointTeam !== null && playerTeam === pointTeam;
  }
  function hasKeysForPoint() {
    var _a;
    const pointGuid = (_a = document.querySelector(".info")) == null ? void 0 : _a.getAttribute("data-guid");
    if (!pointGuid) return false;
    return readInventoryReferences().some((ref) => ref.l === pointGuid);
  }
  const repairAtFullCharge = {
    id: MODULE_ID$6,
    name: { en: "Repair at Full Charge", ru: "Зарядка при полном заряде" },
    description: {
      en: "Repair button stays enabled even at 100% charge — allows recharging immediately without waiting for status update",
      ru: "Кнопка «Починить» активна даже при 100% заряде — позволяет зарядить точку сразу, не дожидаясь обновления статуса"
    },
    defaultEnabled: true,
    category: "feature",
    init() {
    },
    enable() {
      observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
          if (mutation.type === "attributes" && mutation.target instanceof Element && mutation.target.id === "repair") {
            if (isSameTeam() && hasKeysForPoint()) {
              mutation.target.removeAttribute("disabled");
            }
          }
        }
      });
      observer.observe(document.body, {
        subtree: true,
        attributes: true,
        attributeFilter: ["disabled"]
      });
    },
    disable() {
      observer == null ? void 0 : observer.disconnect();
      observer = null;
    }
  };
  const MODULE_ID$5 = "singleFingerRotation";
  let viewport = null;
  let map$1 = null;
  let dragPanControl = null;
  let latestPoint = null;
  let pendingDelta = 0;
  let frameRequestId = null;
  let originalCalculateExtent = null;
  function isFollowActive() {
    return localStorage.getItem("follow") !== "false";
  }
  function getScreenCenter() {
    const padding = (map$1 ? map$1.getView().padding : void 0) ?? [0, 0, 0, 0];
    const [top, right, bottom, left] = padding;
    return {
      x: (left + window.innerWidth - right) / 2,
      y: (top + window.innerHeight - bottom) / 2
    };
  }
  function angleFromCenter(clientX, clientY) {
    const center = getScreenCenter();
    return Math.atan2(clientY - center.y, clientX - center.x);
  }
  function normalizeAngleDelta(delta) {
    if (delta > Math.PI) return delta - 2 * Math.PI;
    if (delta < -Math.PI) return delta + 2 * Math.PI;
    return delta;
  }
  function applyRotation(delta) {
    if (!map$1) return;
    const view = map$1.getView();
    view.setRotation(view.getRotation() + delta);
  }
  function applyPendingRotation() {
    frameRequestId = null;
    const delta = pendingDelta;
    pendingDelta = 0;
    if (delta !== 0) {
      applyRotation(delta);
    }
  }
  function flushPendingRotation() {
    if (frameRequestId !== null) {
      cancelAnimationFrame(frameRequestId);
      frameRequestId = null;
    }
    if (pendingDelta !== 0) {
      applyRotation(pendingDelta);
      pendingDelta = 0;
    }
  }
  function scheduleRotationFrame() {
    if (frameRequestId === null) {
      frameRequestId = requestAnimationFrame(applyPendingRotation);
    }
  }
  function resetGesture() {
    flushPendingRotation();
    latestPoint = null;
    dragPanControl == null ? void 0 : dragPanControl.restore();
  }
  function onTouchStart(event) {
    if (event.targetTouches.length > 1) {
      resetGesture();
      return;
    }
    if (!isFollowActive()) return;
    if (!(event.target instanceof HTMLCanvasElement)) return;
    const touch = event.targetTouches[0];
    latestPoint = [touch.clientX, touch.clientY];
    dragPanControl == null ? void 0 : dragPanControl.disable();
  }
  function onTouchMove(event) {
    if (!latestPoint) return;
    event.preventDefault();
    const touch = event.targetTouches[0];
    const currentAngle = angleFromCenter(touch.clientX, touch.clientY);
    const previousAngle = angleFromCenter(latestPoint[0], latestPoint[1]);
    const delta = normalizeAngleDelta(currentAngle - previousAngle);
    pendingDelta += delta;
    scheduleRotationFrame();
    latestPoint = [touch.clientX, touch.clientY];
  }
  function onTouchEnd() {
    resetGesture();
  }
  function addListeners() {
    if (!viewport) return;
    viewport.addEventListener("touchstart", onTouchStart);
    viewport.addEventListener("touchmove", onTouchMove, { passive: false });
    viewport.addEventListener("touchend", onTouchEnd);
  }
  function removeListeners() {
    if (!viewport) return;
    viewport.removeEventListener("touchstart", onTouchStart);
    viewport.removeEventListener("touchmove", onTouchMove);
    viewport.removeEventListener("touchend", onTouchEnd);
  }
  function installCalculateExtentWrapper() {
    if (!map$1 || originalCalculateExtent !== null) return;
    const view = map$1.getView();
    const original = view.calculateExtent;
    originalCalculateExtent = original;
    view.calculateExtent = (size) => {
      if (size) {
        const diagonal = Math.ceil(Math.sqrt(size[0] ** 2 + size[1] ** 2));
        return original.call(view, [diagonal, diagonal]);
      }
      return original.call(view, size);
    };
  }
  function restoreCalculateExtentWrapper() {
    if (!map$1 || originalCalculateExtent === null) return;
    const view = map$1.getView();
    view.calculateExtent = originalCalculateExtent;
    originalCalculateExtent = null;
  }
  const singleFingerRotation = {
    id: MODULE_ID$5,
    name: {
      en: "Single-Finger Map Rotation",
      ru: "Вращение карты одним пальцем"
    },
    description: {
      en: "Rotate map with circular finger gesture in FW mode",
      ru: "Вращение карты круговым жестом одного пальца в режиме следования за игроком"
    },
    defaultEnabled: true,
    category: "map",
    init() {
      originalCalculateExtent = null;
      dragPanControl = null;
      return getOlMap().then((olMap2) => {
        map$1 = olMap2;
        const viewportElement = $(".ol-viewport");
        if (viewportElement instanceof HTMLElement) {
          viewport = viewportElement;
        }
      });
    },
    enable() {
      if (!map$1) return;
      installCalculateExtentWrapper();
      if (dragPanControl === null) {
        dragPanControl = createDragPanControl(map$1);
      }
      addListeners();
    },
    disable() {
      removeListeners();
      dragPanControl == null ? void 0 : dragPanControl.restore();
      dragPanControl = null;
      resetGesture();
      restoreCalculateExtentWrapper();
    }
  };
  const styles$3 = ".svp-tile-url-input{width:100%;box-sizing:border-box;padding:4px 6px;font-size:12px;font-family:inherit;background:var(--background);color:var(--text);border:1px solid var(--border);border-radius:4px;resize:none}.svp-tile-url-input::placeholder{color:var(--text-disabled)}";
  const MODULE_ID$4 = "mapTileLayers";
  const STORAGE_KEY_URL = "svp_mapTileLayerUrl";
  const STORAGE_KEY_LAYER = "svp_mapTileLayer";
  const STORAGE_KEY_GAME_LAYER = "svp_mapTileGameLayer";
  const CUSTOM_VALUE = "svp-custom";
  const CUSTOM_DARK_VALUE = "svp-custom-dark";
  const TILE_FILTER_ID = "mapTileLayersFilter";
  const LIGHT_FILTER_CSS = ".ol-layer__base canvas { filter: none !important; }";
  const DARK_FILTER_CSS = ".ol-layer__base canvas { filter: invert(1) hue-rotate(180deg) !important; }";
  const LABEL_CUSTOM = { en: "Custom tiles", ru: "Свои тайлы" };
  const LABEL_CUSTOM_DARK = { en: "Custom tiles (dark)", ru: "Свои тайлы (тёмная)" };
  let enabled = false;
  let gameTileLayer = null;
  let originalSource = null;
  let originalSetSource = null;
  let gameRequestedSource = null;
  let hasGameRequest = false;
  let popupObserver$2 = null;
  let injectedElements = [];
  let boundChangeHandler = null;
  let changeTarget = null;
  let lastGameRadioValue = null;
  function findBaseTileLayer(olMap2) {
    for (const layer of olMap2.getLayers().getArray()) {
      if (layer.get("name") === "points") continue;
      if (hasTileSource(layer)) return layer;
    }
    return null;
  }
  function loadSelectedLayer() {
    return localStorage.getItem(STORAGE_KEY_LAYER);
  }
  function loadTileUrl() {
    return localStorage.getItem(STORAGE_KEY_URL) ?? "";
  }
  function isCustomValue(value) {
    return value === CUSTOM_VALUE || value === CUSTOM_DARK_VALUE;
  }
  function lockGameSource() {
    if (!gameTileLayer || originalSetSource) return;
    originalSource = gameTileLayer.getSource();
    originalSetSource = gameTileLayer.setSource.bind(gameTileLayer);
    gameTileLayer.setSource = (source) => {
      gameRequestedSource = source;
      hasGameRequest = true;
    };
  }
  function unlockGameSource(forceOriginal = false) {
    if (!gameTileLayer || !originalSetSource) return;
    gameTileLayer.setSource = originalSetSource;
    if (!forceOriginal && hasGameRequest) {
      gameTileLayer.setSource(gameRequestedSource);
    } else {
      gameTileLayer.setSource(originalSource);
    }
    originalSetSource = null;
    gameRequestedSource = null;
    hasGameRequest = false;
  }
  function applyTileSource(url, variant) {
    var _a, _b;
    const OlXyz = (_b = (_a = window.ol) == null ? void 0 : _a.source) == null ? void 0 : _b.XYZ;
    if (!url || !OlXyz || !gameTileLayer) return;
    lockGameSource();
    const source = new OlXyz({ url });
    if (originalSetSource) {
      originalSetSource(source);
    }
    const isDark = variant === CUSTOM_DARK_VALUE;
    injectStyles(isDark ? DARK_FILTER_CSS : LIGHT_FILTER_CSS, TILE_FILTER_ID);
  }
  function removeCustomTiles() {
    unlockGameSource();
    removeStyles(TILE_FILTER_ID);
  }
  function applyCustomSource() {
    const url = loadTileUrl();
    const variant = loadSelectedLayer();
    if (!variant || !isCustomValue(variant)) return;
    applyTileSource(url, variant);
  }
  function updateRadioState(urlInput, radios) {
    const hasUrl = urlInput.value.trim().length > 0;
    for (const radio of radios) {
      radio.disabled = !hasUrl;
    }
  }
  function injectIntoPopup(popup2) {
    const list = popup2.querySelector(".layers-config__list");
    if (!list) return;
    const lastGameRadio = popup2.querySelector(
      'input[name="baselayer"][value="goo"]'
    );
    const insertAfter = (lastGameRadio == null ? void 0 : lastGameRadio.closest(".layers-config__entry")) ?? null;
    if (!insertAfter) return;
    const urlWrapper = document.createElement("div");
    urlWrapper.className = "layers-config__entry svp-tile-url-entry";
    const urlInput = document.createElement("textarea");
    urlInput.className = "svp-tile-url-input";
    urlInput.placeholder = "https://example.com/tiles/{z}/{x}/{y}.png";
    urlInput.value = loadTileUrl();
    urlInput.rows = 2;
    urlWrapper.appendChild(urlInput);
    const customRadios = [];
    function createRadioLabel(value, label) {
      const radioLabel = document.createElement("label");
      radioLabel.className = "layers-config__entry";
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = "baselayer";
      radio.value = value;
      radio.disabled = !urlInput.value.trim();
      customRadios.push(radio);
      const span = document.createElement("span");
      span.textContent = t(label);
      radioLabel.append(radio, " ", span);
      return radioLabel;
    }
    const customLabel = createRadioLabel(CUSTOM_VALUE, LABEL_CUSTOM);
    const customDarkLabel = createRadioLabel(CUSTOM_DARK_VALUE, LABEL_CUSTOM_DARK);
    urlInput.addEventListener("input", () => {
      updateRadioState(urlInput, customRadios);
      const checkedCustom = customRadios.find((r) => r.checked);
      if (checkedCustom) {
        const url = urlInput.value.trim();
        if (url) {
          localStorage.setItem(STORAGE_KEY_URL, url);
          applyTileSource(url, checkedCustom.value);
        }
      }
    });
    const checkedGameRadio = list.querySelector('input[name="baselayer"]:checked');
    if (checkedGameRadio && !isCustomValue(checkedGameRadio.value)) {
      lastGameRadioValue = checkedGameRadio.value;
    }
    const saved = loadSelectedLayer();
    if (saved && isCustomValue(saved)) {
      const targetRadio = customRadios.find((r) => r.value === saved);
      if (targetRadio && !targetRadio.disabled) {
        targetRadio.checked = true;
      }
    }
    insertAfter.after(urlWrapper);
    insertAfter.after(customDarkLabel);
    insertAfter.after(customLabel);
    injectedElements.push(customLabel, customDarkLabel, urlWrapper);
    const handleRadioChange = (event) => {
      const target = event.target;
      if (!(target instanceof HTMLInputElement) || target.name !== "baselayer") return;
      if (isCustomValue(target.value) && target.checked) {
        const url = urlInput.value.trim();
        if (url) {
          if (lastGameRadioValue) {
            localStorage.setItem(STORAGE_KEY_GAME_LAYER, lastGameRadioValue);
          }
          localStorage.setItem(STORAGE_KEY_URL, url);
          localStorage.setItem(STORAGE_KEY_LAYER, target.value);
          applyTileSource(url, target.value);
        }
      } else if (target.checked) {
        lastGameRadioValue = target.value;
        localStorage.removeItem(STORAGE_KEY_GAME_LAYER);
        localStorage.removeItem(STORAGE_KEY_LAYER);
        removeCustomTiles();
      }
    };
    boundChangeHandler = handleRadioChange;
    changeTarget = list;
    list.addEventListener("change", handleRadioChange);
  }
  function cleanupInjected() {
    if (changeTarget && boundChangeHandler) {
      changeTarget.removeEventListener("change", boundChangeHandler);
      boundChangeHandler = null;
      changeTarget = null;
    }
    for (const element of injectedElements) {
      element.remove();
    }
    injectedElements = [];
  }
  function restoreGameRadioSelection() {
    const savedValue = lastGameRadioValue ?? localStorage.getItem(STORAGE_KEY_GAME_LAYER);
    if (!savedValue) return;
    const popup2 = document.querySelector(".layers-config");
    if (!popup2) return;
    const radios = popup2.querySelectorAll('input[name="baselayer"]');
    for (const radio of radios) {
      if (radio.value === savedValue) {
        radio.checked = true;
        radio.dispatchEvent(new Event("change", { bubbles: true }));
        break;
      }
    }
  }
  function onMutation(mutations) {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node instanceof HTMLElement && node.classList.contains("layers-config")) {
          injectIntoPopup(node);
          return;
        }
      }
      for (const node of mutation.removedNodes) {
        if (node instanceof HTMLElement && node.classList.contains("layers-config")) {
          cleanupInjected();
          return;
        }
      }
    }
  }
  const mapTileLayers = {
    id: MODULE_ID$4,
    name: { en: "Custom map tiles", ru: "Свои тайлы карты" },
    description: {
      en: "Adds custom tile layers to the map layer switcher",
      ru: "Добавляет свои тайлы карты в переключатель слоёв"
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    enable() {
      enabled = true;
      return getOlMap().then((olMap2) => {
        if (!enabled) return;
        gameTileLayer = findBaseTileLayer(olMap2);
        if (!gameTileLayer) return;
        originalSource = gameTileLayer.getSource();
        const saved = loadSelectedLayer();
        const url = loadTileUrl();
        if (saved && isCustomValue(saved) && url) {
          applyCustomSource();
        }
        injectStyles(styles$3, MODULE_ID$4);
        const existingPopup = document.querySelector(".layers-config");
        if (existingPopup) {
          injectIntoPopup(existingPopup);
        }
        popupObserver$2 = new MutationObserver(onMutation);
        popupObserver$2.observe(document.body, { childList: true });
      });
    },
    disable() {
      enabled = false;
      unlockGameSource(true);
      removeStyles(TILE_FILTER_ID);
      removeStyles(MODULE_ID$4);
      cleanupInjected();
      restoreGameRadioSelection();
      popupObserver$2 == null ? void 0 : popupObserver$2.disconnect();
      popupObserver$2 = null;
      gameTileLayer = null;
      originalSource = null;
      lastGameRadioValue = null;
    }
  };
  class IitcParseError extends Error {
    constructor(reason, path, value) {
      super(`${reason} at ${path}`);
      this.reason = reason;
      this.path = path;
      this.value = value;
      this.name = "IitcParseError";
    }
  }
  function isRecord$2(value) {
    return typeof value === "object" && value !== null;
  }
  function isLatLng(value) {
    if (!isRecord$2(value)) return false;
    return typeof value.lat === "number" && typeof value.lng === "number";
  }
  function normalizeColor(value) {
    if (typeof value !== "string") return null;
    if (/^#[0-9a-fA-F]{6}$/.test(value)) return value;
    if (/^#[0-9a-fA-F]{3}$/.test(value)) {
      const r = value[1];
      const g = value[2];
      const b = value[3];
      return `#${r}${r}${g}${g}${b}${b}`;
    }
    return null;
  }
  function validateDrawItem(item, index) {
    const path = `items[${index}]`;
    if (!isRecord$2(item)) {
      throw new IitcParseError("not_object", path, item);
    }
    if (item.type !== "polyline" && item.type !== "polygon") {
      throw new IitcParseError("unsupported_type", path, item.type);
    }
    if (!Array.isArray(item.latLngs)) {
      throw new IitcParseError("lat_lngs_not_array", path, item.latLngs);
    }
    if (item.type === "polyline" && item.latLngs.length < 2) {
      throw new IitcParseError("polyline_too_few_points", path, item.latLngs.length);
    }
    if (item.type === "polygon" && item.latLngs.length < 3) {
      throw new IitcParseError("polygon_too_few_points", path, item.latLngs.length);
    }
    const badIndex = item.latLngs.findIndex((coord) => !isLatLng(coord));
    if (badIndex >= 0) {
      throw new IitcParseError("invalid_coordinates", path, item.latLngs[badIndex]);
    }
    let color;
    if (item.color !== void 0) {
      const normalized = normalizeColor(item.color);
      if (normalized === null) {
        throw new IitcParseError("invalid_color", path, item.color);
      }
      color = normalized;
    }
    return {
      type: item.type,
      latLngs: item.latLngs,
      ...color !== void 0 ? { color } : {}
    };
  }
  function parseIitcDrawItems(raw) {
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      throw new IitcParseError("invalid_json", "root", raw);
    }
    if (!Array.isArray(parsed)) {
      throw new IitcParseError("not_array", "root", parsed);
    }
    return parsed.map(validateDrawItem);
  }
  function stringifyIitcDrawItems(items) {
    return JSON.stringify(items);
  }
  const SVG_OPEN_TOOLBAR = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="svp-draw-tools-icon">';
  const SVG_OPEN_CONTROL = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="svp-draw-tools-icon">';
  const SVG_CLOSE = "</svg>";
  const NO_FILL = ' style="fill:none"';
  function svg(body) {
    return `${SVG_OPEN_TOOLBAR}${body}${SVG_CLOSE}`;
  }
  function svgControl(body) {
    return `${SVG_OPEN_CONTROL}${body}${SVG_CLOSE}`;
  }
  const ICON_LINE = svg(
    '<circle cx="6" cy="18" r="2.2" fill="currentColor" stroke="none"/><circle cx="18" cy="6" r="2.2" fill="currentColor" stroke="none"/><line x1="7.5" y1="16.5" x2="16.5" y2="7.5"/>'
  );
  const ICON_TRIANGLE = svg(`<polygon${NO_FILL} points="12,4 20,20 4,20"/>`);
  const ICON_EDIT = svg(
    `<path${NO_FILL} d="M12 20h9"/><path${NO_FILL} d="M16.5 3.5a2.121 2.121 0 113 3L7 19l-4 1 1-4z"/>`
  );
  const ICON_DELETE = svg(
    `<path${NO_FILL} d="m7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21"/><path${NO_FILL} d="M22 21H7"/><path${NO_FILL} d="m5 11 9 9"/>`
  );
  const ICON_WAND = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 586 512" width="16" height="16" fill="currentColor" class="svp-draw-tools-icon"><use href="#fas-wand-magic-sparkles"/>' + SVG_CLOSE;
  const ICON_COPY = svg(
    `<rect${NO_FILL} x="9" y="3" width="12" height="14" rx="2"/><rect${NO_FILL} x="3" y="7" width="12" height="14" rx="2"/>`
  );
  const ICON_UPLOAD = svg(
    `<path${NO_FILL} d="M12 16V4"/><path${NO_FILL} d="M7 9l5-5 5 5"/><line x1="3" y1="20" x2="21" y2="20"/>`
  );
  const ICON_RESET = svg(
    `<line x1="3" y1="6" x2="21" y2="6"/><path${NO_FILL} d="M5 6l1 14h12l1-14"/><path${NO_FILL} d="M9 6V4a1 1 0 011-1h4a1 1 0 011 1v2"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/>`
  );
  const ICON_CLOSE_X = svg(
    '<line x1="6" y1="6" x2="18" y2="18"/><line x1="6" y1="18" x2="18" y2="6"/>'
  );
  const ICON_DRAW_TOOLS = svgControl(
    `<path${NO_FILL} d="M3 19c2-2 4-2 6 0s4 2 6 0"/><path${NO_FILL} d="M14 5l4 4-7 7-5 1 1-5z"/>`
  );
  const styles$2 = ".svp-draw-tools-control button{width:33px;height:33px;min-height:auto;padding:0}.svp-draw-tools-control button svg{display:block;margin:auto}.svp-draw-tools-icon{display:block}.svp-draw-tools-toolbar{position:fixed;top:122px;left:3px;z-index:2;display:none;flex-wrap:wrap;max-width:calc(100vw - 16px);align-items:center;gap:4px;padding:6px;border-radius:6px;border:1px solid var(--border);background:var(--background)}.svp-draw-tools-toolbar.svp-draw-tools-toolbar-open{display:flex}.svp-draw-tools-toolbar.svp-draw-tools-toolbar-compact-position{top:56px;left:8px}.svp-draw-tools-tool-button{min-width:30px;height:28px;display:inline-flex;align-items:center;justify-content:center;border:1px solid var(--border);border-radius:4px;background:transparent;color:var(--text);cursor:pointer;font-size:12px}.svp-draw-tools-tool-active{border-color:var(--accent);color:var(--accent)}.svp-draw-tools-color{width:30px;height:28px;border:1px solid var(--border);border-radius:4px;padding:0;background:transparent}.svp-draw-tools-copy-modal-overlay{position:fixed;top:0;right:0;bottom:0;left:0;z-index:1003;display:flex;align-items:center;justify-content:center;background:#00000080}.svp-draw-tools-copy-modal{display:flex;flex-direction:column;gap:8px;width:min(90vw,600px);max-height:80vh;padding:16px;border:1px solid var(--border);border-radius:6px;background:var(--background);color:var(--text)}.svp-draw-tools-copy-modal-heading{font-size:14px;font-weight:700}.svp-draw-tools-copy-textarea{width:100%;min-height:200px;flex:1;padding:8px;border:1px solid var(--border);border-radius:4px;background:var(--background);color:var(--text);font-family:monospace;font-size:12px;resize:vertical}.svp-draw-tools-copy-modal-close{align-self:flex-end;padding:4px 12px;border:1px solid var(--border);border-radius:4px;background:transparent;color:var(--text);cursor:pointer}";
  const MODULE_ID$3 = "drawTools";
  const STORAGE_KEY$2 = "svp_drawTools";
  const DRAW_LAYER_NAME = "svp-draw-tools";
  const DRAW_LAYER_Z_INDEX = 9;
  const SNAP_THRESHOLD_PX = 100;
  const DEFAULT_COLOR = "#a24ac3";
  const REGION_PICKER_SELECTOR = ".region-picker.ol-unselectable.ol-control";
  const CONTROL_BUTTON_ID = "svp-draw-tools-menu-button";
  let map = null;
  let drawSource = null;
  let drawLayer = null;
  let controlElement = null;
  let pickerElement = null;
  let controlMutationObserver = null;
  let controlResizeObserver = null;
  let windowResizeHandler = null;
  let toolbar = null;
  let copyModalOverlay = null;
  let copyModalKeydownHandler = null;
  let documentClickHandler = null;
  let compactContainerObserver = null;
  let lineButton = null;
  let polygonButton = null;
  let editButton = null;
  let deleteButton = null;
  let colorInput = null;
  let currentMode = "none";
  let currentColor = DEFAULT_COLOR;
  let drawInteraction = null;
  let modifyInteraction = null;
  let deleteClickHandler = null;
  let drawEndHandler = null;
  let modifyEndHandler = null;
  let enableToken = 0;
  let keydownHandler = null;
  function isRecord$1(value) {
    return typeof value === "object" && value !== null;
  }
  function isNumberPair(value) {
    return Array.isArray(value) && value.length >= 2 && typeof value[0] === "number" && typeof value[1] === "number";
  }
  function isLineGeometry(value) {
    if (!isRecord$1(value)) return false;
    const getType = value.getType;
    const getCoordinates = value.getCoordinates;
    const setCoordinates = value.setCoordinates;
    return typeof getType === "function" && getType() === "LineString" && typeof getCoordinates === "function" && typeof setCoordinates === "function";
  }
  function isPolygonGeometry(value) {
    if (!isRecord$1(value)) return false;
    const getType = value.getType;
    const getCoordinates = value.getCoordinates;
    const setCoordinates = value.setCoordinates;
    return typeof getType === "function" && getType() === "Polygon" && typeof getCoordinates === "function" && typeof setCoordinates === "function";
  }
  function setFeatureColor(feature, color) {
    var _a;
    const withProps = feature;
    (_a = withProps.set) == null ? void 0 : _a.call(withProps, "color", color);
  }
  function getFeatureColor(feature) {
    var _a;
    const withProps = feature;
    const value = (_a = withProps.get) == null ? void 0 : _a.call(withProps, "color");
    return typeof value === "string" ? value : DEFAULT_COLOR;
  }
  function getLatLngFromCoordinate(coordinate) {
    var _a, _b;
    if (typeof ((_b = (_a = window.ol) == null ? void 0 : _a.proj) == null ? void 0 : _b.toLonLat) !== "function") {
      return { lat: coordinate[1], lng: coordinate[0] };
    }
    const lonLat = window.ol.proj.toLonLat(coordinate);
    return { lat: lonLat[1], lng: lonLat[0] };
  }
  function getCoordinateFromLatLng(latLng) {
    var _a, _b;
    const lonLat = [latLng.lng, latLng.lat];
    if (typeof ((_b = (_a = window.ol) == null ? void 0 : _a.proj) == null ? void 0 : _b.fromLonLat) !== "function") return lonLat;
    return window.ol.proj.fromLonLat(lonLat);
  }
  function equalLatLng(a, b) {
    return Math.abs(a.lat - b.lat) < 1e-7 && Math.abs(a.lng - b.lng) < 1e-7;
  }
  function serializeFeature(feature) {
    const geometry = feature.getGeometry();
    const color = getFeatureColor(feature);
    if (isLineGeometry(geometry)) {
      const latLngs = geometry.getCoordinates().map(getLatLngFromCoordinate);
      return { type: "polyline", latLngs, color };
    }
    if (isPolygonGeometry(geometry)) {
      const ring = geometry.getCoordinates()[0] ?? [];
      const latLngs = ring.map(getLatLngFromCoordinate);
      if (latLngs.length >= 2 && equalLatLng(latLngs[0], latLngs[latLngs.length - 1])) {
        latLngs.pop();
      }
      return { type: "polygon", latLngs, color };
    }
    return null;
  }
  function getDrawItems() {
    if (!drawSource) return [];
    const items = [];
    for (const feature of drawSource.getFeatures()) {
      const item = serializeFeature(feature);
      if (item) items.push(item);
    }
    return items;
  }
  function saveDrawItems() {
    localStorage.setItem(STORAGE_KEY$2, stringifyIitcDrawItems(getDrawItems()));
  }
  function getStorageRaw() {
    return localStorage.getItem(STORAGE_KEY$2) ?? "[]";
  }
  function clearDrawLayer() {
    drawSource == null ? void 0 : drawSource.clear();
  }
  function ensurePolygonClosed(latLngs) {
    const first = latLngs[0];
    const last = latLngs[latLngs.length - 1];
    if (equalLatLng(first, last)) return latLngs;
    return [...latLngs, first];
  }
  function importDrawItems(items) {
    var _a, _b, _c, _d, _e;
    const OlFeature = (_a = window.ol) == null ? void 0 : _a.Feature;
    const OlLineString = (_c = (_b = window.ol) == null ? void 0 : _b.geom) == null ? void 0 : _c.LineString;
    const OlPolygon = (_e = (_d = window.ol) == null ? void 0 : _d.geom) == null ? void 0 : _e.Polygon;
    if (!drawSource || !OlFeature || !OlLineString || !OlPolygon) return;
    for (const item of items) {
      if (item.type === "polyline") {
        const coordinates2 = item.latLngs.map(getCoordinateFromLatLng);
        const geometry2 = new OlLineString(coordinates2);
        const feature2 = new OlFeature({ geometry: geometry2 });
        setFeatureColor(feature2, item.color ?? DEFAULT_COLOR);
        drawSource.addFeature(feature2);
        continue;
      }
      const closed = ensurePolygonClosed(item.latLngs);
      const coordinates = closed.map(getCoordinateFromLatLng);
      const geometry = new OlPolygon([coordinates]);
      const feature = new OlFeature({ geometry });
      setFeatureColor(feature, item.color ?? DEFAULT_COLOR);
      drawSource.addFeature(feature);
    }
  }
  function loadFromStorage() {
    const raw = getStorageRaw();
    try {
      const items = parseIitcDrawItems(raw);
      clearDrawLayer();
      importDrawItems(items);
    } catch {
      clearDrawLayer();
      localStorage.setItem(STORAGE_KEY$2, "[]");
    }
  }
  function createStyleFunction() {
    var _a;
    const styleApi = (_a = window.ol) == null ? void 0 : _a.style;
    if (!(styleApi == null ? void 0 : styleApi.Style) || !styleApi.Stroke || !styleApi.Fill) return null;
    const OlStyle = styleApi.Style;
    const OlStroke = styleApi.Stroke;
    const OlFill = styleApi.Fill;
    return (feature) => {
      const color = getFeatureColor(feature);
      const geometry = feature.getGeometry();
      const isPolygon = isPolygonGeometry(geometry);
      return new OlStyle({
        stroke: new OlStroke({ color, width: 4 }),
        fill: new OlFill({ color: isPolygon ? color + "33" : "transparent" })
      });
    };
  }
  function createDrawInteractionStyle(color) {
    var _a;
    const styleApi = (_a = window.ol) == null ? void 0 : _a.style;
    if (!(styleApi == null ? void 0 : styleApi.Style) || !styleApi.Stroke || !styleApi.Fill || !styleApi.Circle) {
      return void 0;
    }
    const OlStyle = styleApi.Style;
    const OlStroke = styleApi.Stroke;
    const OlFill = styleApi.Fill;
    const OlCircle = styleApi.Circle;
    return [
      new OlStyle({
        stroke: new OlStroke({ color, width: 4 }),
        fill: new OlFill({ color: color + "33" }),
        image: new OlCircle({
          radius: 5,
          fill: new OlFill({ color }),
          stroke: new OlStroke({ color, width: 2 })
        })
      })
    ];
  }
  function createDrawLayer(olMap2) {
    var _a, _b, _c, _d;
    const OlVectorSource = (_b = (_a = window.ol) == null ? void 0 : _a.source) == null ? void 0 : _b.Vector;
    const OlVectorLayer = (_d = (_c = window.ol) == null ? void 0 : _c.layer) == null ? void 0 : _d.Vector;
    if (!OlVectorSource || !OlVectorLayer) {
      throw new Error("OL Vector API is unavailable");
    }
    const source = new OlVectorSource();
    const style = createStyleFunction();
    drawSource = source;
    drawLayer = new OlVectorLayer({
      source,
      name: DRAW_LAYER_NAME,
      zIndex: DRAW_LAYER_Z_INDEX,
      style: style ?? void 0
    });
    olMap2.addLayer(drawLayer);
  }
  function removeDrawLayer() {
    if (map && drawLayer) {
      map.removeLayer(drawLayer);
    }
    drawLayer = null;
    drawSource = null;
  }
  function updateModeButtons() {
    const defs = [
      ["line", lineButton],
      ["polygon", polygonButton],
      ["edit", editButton],
      ["delete", deleteButton]
    ];
    for (const [mode, button] of defs) {
      if (!button) continue;
      button.classList.toggle("svp-draw-tools-tool-active", currentMode === mode);
    }
  }
  function cancelActiveDrawing() {
    if (currentMode !== "line" && currentMode !== "polygon") return;
    if (!drawInteraction) return;
    if (typeof drawInteraction.abortDrawing === "function") {
      drawInteraction.abortDrawing();
      return;
    }
    setMode(currentMode, true);
  }
  function clearInteractions() {
    var _a, _b, _c, _d, _e;
    if (!map) return;
    if (drawInteraction) {
      if (drawEndHandler) {
        (_a = drawInteraction.un) == null ? void 0 : _a.call(drawInteraction, "drawend", drawEndHandler);
      }
      (_b = map.removeInteraction) == null ? void 0 : _b.call(map, drawInteraction);
      drawInteraction = null;
      drawEndHandler = null;
    }
    if (modifyInteraction) {
      if (modifyEndHandler) {
        (_c = modifyInteraction.un) == null ? void 0 : _c.call(modifyInteraction, "modifyend", modifyEndHandler);
      }
      (_d = map.removeInteraction) == null ? void 0 : _d.call(map, modifyInteraction);
      modifyInteraction = null;
      modifyEndHandler = null;
    }
    if (deleteClickHandler) {
      (_e = map.un) == null ? void 0 : _e.call(map, "click", deleteClickHandler);
      deleteClickHandler = null;
    }
  }
  function setMode(mode, force = false) {
    var _a, _b, _c, _d, _e, _f;
    if (!force && currentMode === mode) {
      mode = "none";
    }
    clearInteractions();
    currentMode = mode;
    updateModeButtons();
    if (!map || !drawSource || mode === "none") return;
    const interactionApi = (_a = window.ol) == null ? void 0 : _a.interaction;
    if (!interactionApi) return;
    if (mode === "line" || mode === "polygon") {
      const DrawCtor = interactionApi.Draw;
      if (!DrawCtor) return;
      const maxPoints = mode === "line" ? 2 : 3;
      drawInteraction = new DrawCtor({
        source: drawSource,
        type: mode === "line" ? "LineString" : "Polygon",
        maxPoints,
        style: createDrawInteractionStyle(currentColor)
      });
      drawEndHandler = (event) => {
        setFeatureColor(event.feature, currentColor);
        saveDrawItems();
      };
      (_b = drawInteraction.on) == null ? void 0 : _b.call(drawInteraction, "drawend", drawEndHandler);
      (_c = map.addInteraction) == null ? void 0 : _c.call(map, drawInteraction);
      return;
    }
    if (mode === "edit") {
      const ModifyCtor = interactionApi.Modify;
      if (!ModifyCtor) return;
      modifyInteraction = new ModifyCtor({
        source: drawSource,
        insertVertexCondition: () => false
      });
      modifyEndHandler = () => {
        saveDrawItems();
      };
      (_d = modifyInteraction.on) == null ? void 0 : _d.call(modifyInteraction, "modifyend", modifyEndHandler);
      (_e = map.addInteraction) == null ? void 0 : _e.call(map, modifyInteraction);
      return;
    }
    deleteClickHandler = (event) => {
      if (!(map == null ? void 0 : map.forEachFeatureAtPixel) || !drawSource) return;
      const source = drawSource;
      map.forEachFeatureAtPixel(
        event.pixel,
        (feature) => {
          var _a2;
          (_a2 = source.removeFeature) == null ? void 0 : _a2.call(source, feature);
        },
        {
          hitTolerance: 6,
          layerFilter: (layer) => layer.get("name") === DRAW_LAYER_NAME
        }
      );
      saveDrawItems();
    };
    (_f = map.on) == null ? void 0 : _f.call(map, "click", deleteClickHandler);
  }
  function addEscCancelListener() {
    if (keydownHandler) return;
    keydownHandler = (event) => {
      if (event.key !== "Escape") return;
      cancelActiveDrawing();
    };
    document.addEventListener("keydown", keydownHandler);
  }
  function removeEscCancelListener() {
    if (!keydownHandler) return;
    document.removeEventListener("keydown", keydownHandler);
    keydownHandler = null;
  }
  function isInsideMap(target) {
    const mapElement = document.getElementById("map");
    return mapElement !== null && mapElement.contains(target);
  }
  function addToolbarOutsideClickListener() {
    if (documentClickHandler) return;
    documentClickHandler = (event) => {
      if (!(toolbar == null ? void 0 : toolbar.classList.contains("svp-draw-tools-toolbar-open"))) return;
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (toolbar.contains(target)) return;
      if (controlElement == null ? void 0 : controlElement.contains(target)) return;
      if (isInsideMap(target)) return;
      const followButton = document.getElementById("toggle-follow-btn");
      if (followButton !== null && followButton.contains(target)) return;
      const toast = target instanceof Element ? target.closest(".svp-toast") : null;
      if (toast !== null) return;
      const popup2 = target instanceof Element ? target.closest(".popup, .popup-touch") : null;
      if (popup2 !== null) return;
      setToolbarOpen(false);
      setMode("none");
    };
    document.addEventListener("click", documentClickHandler);
  }
  function removeToolbarOutsideClickListener() {
    if (!documentClickHandler) return;
    document.removeEventListener("click", documentClickHandler);
    documentClickHandler = null;
  }
  function buildVertexSnaps(vertices, portalCoordinates) {
    const currentMap = map;
    if (!currentMap || !currentMap.getPixelFromCoordinate) return [];
    const convertToPixel = currentMap.getPixelFromCoordinate.bind(currentMap);
    const portalPixels = portalCoordinates.map((coord) => {
      const px = convertToPixel(coord);
      return isNumberPair(px) ? px : null;
    });
    const snaps = [];
    for (let vertexIndex = 0; vertexIndex < vertices.length; vertexIndex++) {
      const vertexPixel = convertToPixel(vertices[vertexIndex]);
      if (!isNumberPair(vertexPixel)) continue;
      const candidates = [];
      for (let portalIndex = 0; portalIndex < portalCoordinates.length; portalIndex++) {
        const portalPixel = portalPixels[portalIndex];
        if (!portalPixel) continue;
        const dx = portalPixel[0] - vertexPixel[0];
        const dy = portalPixel[1] - vertexPixel[1];
        const distancePx = Math.sqrt(dx * dx + dy * dy);
        if (distancePx <= SNAP_THRESHOLD_PX) {
          candidates.push({ portalIndex, distancePx });
        }
      }
      candidates.sort((a, b) => a.distancePx - b.distancePx);
      snaps.push({ vertexIndex, candidates });
    }
    snaps.sort((a, b) => {
      var _a, _b;
      const bestA = ((_a = a.candidates[0]) == null ? void 0 : _a.distancePx) ?? Infinity;
      const bestB = ((_b = b.candidates[0]) == null ? void 0 : _b.distancePx) ?? Infinity;
      return bestA - bestB;
    });
    return snaps;
  }
  function snapVertices(vertices, portalCoordinates) {
    const result = vertices.map((v) => [...v]);
    const snaps = buildVertexSnaps(vertices, portalCoordinates);
    const claimedPortals = /* @__PURE__ */ new Set();
    let moved = 0;
    for (const snap of snaps) {
      for (const candidate of snap.candidates) {
        if (!claimedPortals.has(candidate.portalIndex)) {
          result[snap.vertexIndex] = portalCoordinates[candidate.portalIndex];
          claimedPortals.add(candidate.portalIndex);
          moved++;
          break;
        }
      }
    }
    return { result, moved };
  }
  function getPortalCoordinates() {
    if (!map) return [];
    const pointsLayer = findLayerByName(map, "points");
    const source = pointsLayer == null ? void 0 : pointsLayer.getSource();
    if (!source) return [];
    const result = [];
    for (const feature of source.getFeatures()) {
      const coordinates = feature.getGeometry().getCoordinates();
      if (isNumberPair(coordinates)) {
        result.push([coordinates[0], coordinates[1]]);
      }
    }
    return result;
  }
  function snapAllToPortals() {
    if (!drawSource) return;
    const portalCoordinates = getPortalCoordinates();
    if (portalCoordinates.length === 0) {
      showToast(t({ en: "No visible portals for snap", ru: "Нет видимых точек для привязки" }));
      return;
    }
    let moved = 0;
    for (const feature of drawSource.getFeatures()) {
      const geometry = feature.getGeometry();
      if (isLineGeometry(geometry)) {
        const { result, moved: count } = snapVertices(geometry.getCoordinates(), portalCoordinates);
        geometry.setCoordinates(result);
        moved += count;
        continue;
      }
      if (isPolygonGeometry(geometry)) {
        const ring = geometry.getCoordinates()[0] ?? [];
        const isClosedRing = ring.length > 1 && ring[0][0] === ring[ring.length - 1][0] && ring[0][1] === ring[ring.length - 1][1];
        const openRing = isClosedRing ? ring.slice(0, -1) : ring;
        const { result, moved: count } = snapVertices(openRing, portalCoordinates);
        const closedResult = isClosedRing && result.length > 0 ? [...result, result[0]] : result;
        geometry.setCoordinates([closedResult]);
        moved += count;
      }
    }
    if (moved > 0) {
      saveDrawItems();
    }
    showToast(
      t({
        en: `Snap complete: vertices moved — ${moved}`,
        ru: `Привязка завершена: перемещено вершин — ${moved}`
      })
    );
  }
  function closeCopyFallbackModal() {
    if (copyModalKeydownHandler) {
      document.removeEventListener("keydown", copyModalKeydownHandler);
      copyModalKeydownHandler = null;
    }
    if (copyModalOverlay) {
      copyModalOverlay.remove();
      copyModalOverlay = null;
    }
  }
  function showCopyFallbackModal(text) {
    closeCopyFallbackModal();
    const overlay = document.createElement("div");
    overlay.className = "svp-draw-tools-copy-modal-overlay";
    const modal = document.createElement("div");
    modal.className = "svp-draw-tools-copy-modal";
    const heading = document.createElement("div");
    heading.className = "svp-draw-tools-copy-modal-heading";
    heading.textContent = t({
      en: "Copy this JSON",
      ru: "Скопируйте JSON"
    });
    const textarea = document.createElement("textarea");
    textarea.className = "svp-draw-tools-copy-textarea";
    textarea.readOnly = true;
    textarea.value = text;
    const closeButton2 = document.createElement("button");
    closeButton2.type = "button";
    closeButton2.className = "svp-draw-tools-copy-modal-close";
    closeButton2.textContent = t({ en: "Close", ru: "Закрыть" });
    closeButton2.addEventListener("click", closeCopyFallbackModal);
    modal.append(heading, textarea, closeButton2);
    overlay.appendChild(modal);
    overlay.addEventListener("click", (event) => {
      if (event.target === overlay) closeCopyFallbackModal();
    });
    copyModalKeydownHandler = (event) => {
      if (event.key !== "Escape") return;
      closeCopyFallbackModal();
    };
    document.addEventListener("keydown", copyModalKeydownHandler);
    document.body.appendChild(overlay);
    copyModalOverlay = overlay;
    textarea.focus();
    textarea.select();
  }
  async function copyDrawPlan() {
    const raw = stringifyIitcDrawItems(getDrawItems());
    try {
      await navigator.clipboard.writeText(raw);
      showToast(t({ en: "Copied draw plan", ru: "Схема скопирована" }));
      return;
    } catch {
      showCopyFallbackModal(raw);
    }
  }
  function formatValue(value) {
    if (value === null) return "null";
    if (value === void 0) return "undefined";
    try {
      return JSON.stringify(value);
    } catch {
      return "<unserializable>";
    }
  }
  function importErrorDetail(error) {
    if (!(error instanceof IitcParseError)) {
      return { en: "invalid data", ru: "некорректные данные" };
    }
    const { reason, path, value } = error;
    switch (reason) {
      case "invalid_json":
        return { en: "invalid JSON", ru: "некорректный JSON" };
      case "not_array":
        return { en: "expected an array of items", ru: "ожидается массив элементов" };
      case "not_object":
        return {
          en: `${path} — item must be an object`,
          ru: `${path} — фигура должна быть объектом`
        };
      case "unsupported_type":
        return {
          en: `${path} — unsupported type ${formatValue(value)}`,
          ru: `${path} — неподдерживаемый тип фигуры ${formatValue(value)}`
        };
      case "lat_lngs_not_array":
        return {
          en: `${path} — latLngs must be an array`,
          ru: `${path} — координаты должны быть массивом`
        };
      case "polyline_too_few_points":
        return {
          en: `${path} — line needs at least 2 points, got ${String(value)}`,
          ru: `${path} — для линии нужно минимум 2 точки, передано ${String(value)}`
        };
      case "polygon_too_few_points":
        return {
          en: `${path} — triangle needs at least 3 points, got ${String(value)}`,
          ru: `${path} — для треугольника нужно минимум 3 точки, передано ${String(value)}`
        };
      case "invalid_coordinates":
        return {
          en: `${path} — invalid coordinate ${formatValue(value)}`,
          ru: `${path} — некорректные координаты ${formatValue(value)}`
        };
      case "invalid_color":
        return {
          en: `${path} — invalid color ${formatValue(value)} (expected #RRGGBB or #RGB)`,
          ru: `${path} — некорректный цвет ${formatValue(value)} (требуется #RRGGBB или #RGB)`
        };
    }
  }
  function pasteDrawPlan() {
    const raw = window.prompt(
      t({
        en: "Paste IITC draw-tools JSON",
        ru: "Вставьте JSON draw-tools (IITC)"
      }),
      ""
    );
    if (!raw) return;
    let items;
    try {
      items = parseIitcDrawItems(raw.trim());
    } catch (error) {
      const detail = importErrorDetail(error);
      showToast(t({ en: `Import failed: ${detail.en}`, ru: `Импорт не удался: ${detail.ru}` }));
      return;
    }
    const hasData = ((drawSource == null ? void 0 : drawSource.getFeatures().length) ?? 0) > 0;
    if (hasData) {
      const ok = confirm(
        t({
          en: "Replace current draw plan with imported data?",
          ru: "Заменить текущую схему импортированной?"
        })
      );
      if (!ok) return;
    }
    clearDrawLayer();
    importDrawItems(items);
    saveDrawItems();
    showToast(t({ en: "Import successful", ru: "Импорт выполнен" }));
  }
  function resetDrawPlan() {
    const hasData = ((drawSource == null ? void 0 : drawSource.getFeatures().length) ?? 0) > 0;
    if (!hasData) return;
    const ok = confirm(
      t({
        en: "Delete all drawn items?",
        ru: "Удалить всю нарисованную схему?"
      })
    );
    if (!ok) return;
    clearDrawLayer();
    saveDrawItems();
    showToast(t({ en: "Draw plan cleared", ru: "Схема очищена" }));
  }
  function setToolbarOpen(open) {
    if (!toolbar) return;
    toolbar.classList.toggle("svp-draw-tools-toolbar-open", open);
  }
  function toggleToolbar() {
    if (!toolbar) return;
    setToolbarOpen(!toolbar.classList.contains("svp-draw-tools-toolbar-open"));
  }
  function applySvgIcon(button, svgString) {
    const parser = new DOMParser();
    const doc = parser.parseFromString(svgString, "image/svg+xml");
    const root = doc.documentElement;
    if (root.tagName.toLowerCase() !== "svg") return;
    button.textContent = "";
    button.appendChild(document.importNode(root, true));
  }
  function createToolButton(iconSvg, title, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "svp-draw-tools-tool-button";
    button.title = title;
    button.addEventListener("click", onClick);
    applySvgIcon(button, iconSvg);
    return button;
  }
  function createToolbar() {
    const panel2 = document.createElement("div");
    panel2.className = "svp-draw-tools-toolbar";
    lineButton = createToolButton(ICON_LINE, t({ en: "Line", ru: "Линия" }), () => {
      setMode("line");
    });
    polygonButton = createToolButton(ICON_TRIANGLE, t({ en: "Triangle", ru: "Треугольник" }), () => {
      setMode("polygon");
    });
    editButton = createToolButton(ICON_EDIT, t({ en: "Edit", ru: "Редактирование" }), () => {
      setMode("edit");
    });
    deleteButton = createToolButton(ICON_DELETE, t({ en: "Delete mode", ru: "Удаление" }), () => {
      setMode("delete");
    });
    colorInput = document.createElement("input");
    colorInput.type = "color";
    colorInput.className = "svp-draw-tools-color";
    colorInput.value = currentColor;
    colorInput.title = t({ en: "Color", ru: "Цвет" });
    colorInput.addEventListener("input", () => {
      if (!colorInput) return;
      currentColor = colorInput.value;
    });
    const snapButton = createToolButton(
      ICON_WAND,
      t({ en: "Snap all to nearest portals (100px)", ru: "Привязать к ближайшим точкам (100px)" }),
      snapAllToPortals
    );
    const copyButton = createToolButton(
      ICON_COPY,
      t({ en: "Copy JSON", ru: "Копировать JSON" }),
      () => {
        void copyDrawPlan();
      }
    );
    const pasteButton = createToolButton(
      ICON_UPLOAD,
      t({ en: "Paste JSON", ru: "Вставить JSON" }),
      pasteDrawPlan
    );
    const resetButton = createToolButton(
      ICON_RESET,
      t({ en: "Clear all", ru: "Очистить всё" }),
      resetDrawPlan
    );
    const closeButton2 = createToolButton(ICON_CLOSE_X, t({ en: "Close", ru: "Закрыть" }), () => {
      setToolbarOpen(false);
      setMode("none");
    });
    closeButton2.classList.add("svp-draw-tools-close-button");
    panel2.append(
      lineButton,
      polygonButton,
      editButton,
      deleteButton,
      colorInput,
      snapButton,
      copyButton,
      pasteButton,
      resetButton,
      closeButton2
    );
    return panel2;
  }
  function syncControlPosition() {
    if (!controlElement || !pickerElement) return;
    const rect = pickerElement.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) return;
    controlElement.style.top = `${rect.bottom}px`;
    controlElement.style.right = `${window.innerWidth - rect.right}px`;
    controlElement.style.left = "auto";
    controlElement.style.bottom = "auto";
  }
  function createControlElement() {
    const element = document.createElement("div");
    element.className = "svp-draw-tools-control ol-unselectable ol-control";
    element.style.position = "fixed";
    const button = document.createElement("button");
    button.type = "button";
    button.id = CONTROL_BUTTON_ID;
    button.className = "svp-draw-tools-control-button";
    button.title = t({ en: "Draw tools", ru: "Инструменты рисования" });
    button.addEventListener("click", toggleToolbar);
    applySvgIcon(button, ICON_DRAW_TOOLS);
    element.appendChild(button);
    return element;
  }
  async function mountOlControl(myToken) {
    let picker = document.querySelector(REGION_PICKER_SELECTOR);
    if (!picker) {
      const found = await waitForElement(REGION_PICKER_SELECTOR);
      if (myToken !== enableToken) return false;
      if (!(found instanceof HTMLElement)) {
        throw new Error("Region picker not found");
      }
      picker = found;
    }
    pickerElement = picker;
    controlElement = createControlElement();
    picker.after(controlElement);
    syncControlPosition();
    controlMutationObserver = new MutationObserver(() => {
      if (!controlElement || !pickerElement) return;
      if (!controlElement.isConnected) {
        pickerElement.after(controlElement);
      }
      syncControlPosition();
    });
    controlMutationObserver.observe(document.body, { childList: true, subtree: true });
    if (typeof ResizeObserver !== "undefined") {
      controlResizeObserver = new ResizeObserver(() => {
        syncControlPosition();
      });
      controlResizeObserver.observe(picker);
    }
    windowResizeHandler = () => {
      syncControlPosition();
    };
    window.addEventListener("resize", windowResizeHandler);
    return true;
  }
  function unmountOlControl() {
    controlMutationObserver == null ? void 0 : controlMutationObserver.disconnect();
    controlMutationObserver = null;
    controlResizeObserver == null ? void 0 : controlResizeObserver.disconnect();
    controlResizeObserver = null;
    if (windowResizeHandler) {
      window.removeEventListener("resize", windowResizeHandler);
      windowResizeHandler = null;
    }
    if (controlElement) {
      const button = controlElement.querySelector("button");
      button == null ? void 0 : button.removeEventListener("click", toggleToolbar);
      controlElement.remove();
      controlElement = null;
    }
    pickerElement = null;
  }
  function applyToolbarPositionClass() {
    if (!toolbar) return;
    const compact = document.querySelector(".topleft-container.svp-compact");
    toolbar.classList.toggle("svp-draw-tools-toolbar-compact-position", compact !== null);
  }
  function watchCompactContainer() {
    if (compactContainerObserver) return;
    const container = document.querySelector(".topleft-container");
    if (!(container instanceof HTMLElement)) return;
    compactContainerObserver = new MutationObserver(applyToolbarPositionClass);
    compactContainerObserver.observe(container, {
      attributes: true,
      attributeFilter: ["class"]
    });
  }
  function unwatchCompactContainer() {
    compactContainerObserver == null ? void 0 : compactContainerObserver.disconnect();
    compactContainerObserver = null;
  }
  function mountToolbar() {
    if (toolbar) return;
    toolbar = createToolbar();
    document.body.appendChild(toolbar);
    applyToolbarPositionClass();
    watchCompactContainer();
  }
  function unmountToolbar() {
    if (!toolbar) return;
    toolbar.remove();
    toolbar = null;
    lineButton = null;
    polygonButton = null;
    editButton = null;
    deleteButton = null;
    colorInput = null;
  }
  function cleanup() {
    enableToken++;
    removeEscCancelListener();
    removeToolbarOutsideClickListener();
    unwatchCompactContainer();
    closeCopyFallbackModal();
    setMode("none");
    clearInteractions();
    unmountToolbar();
    unmountOlControl();
    removeDrawLayer();
    removeStyles(MODULE_ID$3);
    map = null;
  }
  const drawTools = {
    id: MODULE_ID$3,
    name: { en: "Draw tools", ru: "Инструменты рисования" },
    description: {
      en: "Draw and edit plans (2-point lines and 3-point triangles), snap to points, import/export between players",
      ru: "Рисование и редактирование схем (линии из 2 точек и треугольники из 3 точек), привязка к точкам, импорт/экспорт между игроками"
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    async enable() {
      const myToken = ++enableToken;
      injectStyles(styles$2, MODULE_ID$3);
      try {
        mountToolbar();
        const mounted = await mountOlControl(myToken);
        if (!mounted) return;
        const olMap2 = await getOlMap();
        if (myToken !== enableToken) return;
        map = olMap2;
        createDrawLayer(olMap2);
        loadFromStorage();
        addEscCancelListener();
        addToolbarOutsideClickListener();
        updateModeButtons();
      } catch (error) {
        cleanup();
        throw error;
      }
    },
    disable() {
      cleanup();
    }
  };
  const FAVORITES_CHANGED_EVENT = "svp:favorites-changed";
  function emitChange() {
    document.dispatchEvent(new CustomEvent(FAVORITES_CHANGED_EVENT));
  }
  const DB_NAME = "CUI";
  const STORE_NAME = "favorites";
  const CUI_DB_VERSION = 9;
  const SEAL_KEY = "svp_favorites_seal";
  function updateSeal() {
    try {
      localStorage.setItem(SEAL_KEY, String(memoryGuids.size));
    } catch {
    }
  }
  function readSeal() {
    try {
      const value = localStorage.getItem(SEAL_KEY);
      if (value === null) return 0;
      const parsed = parseInt(value, 10);
      return Number.isFinite(parsed) ? parsed : 0;
    } catch {
      return 0;
    }
  }
  let memoryGuids = /* @__PURE__ */ new Set();
  let cooldownByGuid = /* @__PURE__ */ new Map();
  let dbPromise = null;
  let snapshotLoaded = false;
  function promisifyRequest(request) {
    return new Promise((resolve, reject) => {
      request.onsuccess = () => {
        resolve(request.result);
      };
      request.onerror = () => {
        reject(request.error ?? new Error("IDB request failed"));
      };
    });
  }
  function waitForTransaction(tx) {
    return new Promise((resolve, reject) => {
      tx.oncomplete = () => {
        resolve();
      };
      tx.onabort = () => {
        reject(tx.error ?? new Error("IDB transaction aborted"));
      };
      tx.onerror = () => {
        reject(tx.error ?? new Error("IDB transaction error"));
      };
    });
  }
  function initializeCuiDb(database, transaction) {
    const created = /* @__PURE__ */ new Set();
    function ensureStore(name, options) {
      if (!database.objectStoreNames.contains(name)) {
        database.createObjectStore(name, options);
        created.add(name);
      }
    }
    ensureStore("config");
    ensureStore("logs", { keyPath: "timestamp" });
    ensureStore("state");
    ensureStore("stats", { keyPath: "name" });
    ensureStore("tiles");
    ensureStore(STORE_NAME, { keyPath: "guid" });
    let isDarkMode = false;
    try {
      const settings = JSON.parse(localStorage.getItem("settings") ?? "{}");
      isDarkMode = typeof settings === "object" && settings !== null && settings.theme === "dark";
    } catch {
    }
    const defaultConfig = {
      maxAmountInBag: {
        cores: { 1: -1, 2: -1, 3: -1, 4: -1, 5: -1, 6: -1, 7: -1, 8: -1, 9: -1, 10: -1 },
        catalysers: { 1: -1, 2: -1, 3: -1, 4: -1, 5: -1, 6: -1, 7: -1, 8: -1, 9: -1, 10: -1 },
        references: { allied: -1, hostile: -1 }
      },
      autoSelect: { deploy: "max", upgrade: "min", attack: "latest" },
      mapFilters: {
        invert: isDarkMode ? 1 : 0,
        hueRotate: 0,
        brightness: isDarkMode ? 0.75 : 1,
        grayscale: isDarkMode ? 1 : 0,
        sepia: 0,
        blur: 0,
        branding: "default",
        brandingColor: "#CCCCCC"
      },
      tinting: { map: 1, point: "team", profile: 1 },
      vibration: { buttons: 1, notifications: 1 },
      ui: {
        chamomile: 1,
        doubleClickZoom: 0,
        restoreRotation: 1,
        pointBgImage: 0,
        pointBtnsRtl: 0,
        pointBgImageBlur: 1,
        pointDischargeTimeout: 1
      },
      pointHighlighting: {
        inner: "uniqc",
        outer: "off",
        outerTop: "cores",
        outerBottom: "highlevel",
        text: "refsAmount",
        innerColor: "#E87100",
        outerColor: "#E87100",
        outerTopColor: "#EB4DBF",
        outerBottomColor: "#28C4F4"
      },
      drawing: {
        returnToPointInfo: "discoverable",
        minDistance: -1,
        maxDistance: -1,
        hideLastFavRef: 0
      },
      notifications: { status: "all", onClick: "jumpto", interval: 3e4, duration: -1 }
    };
    if (created.has("config")) {
      const configStore = transaction.objectStore("config");
      for (const key of Object.keys(defaultConfig)) {
        configStore.add(defaultConfig[key], key);
      }
    }
    if (created.has("logs")) {
      transaction.objectStore("logs").createIndex("action_type", "type");
    }
    if (created.has("state")) {
      const stateStore = transaction.objectStore("state");
      stateStore.add(/* @__PURE__ */ new Set(), "excludedCores");
      stateStore.add(true, "isMainToolbarOpened");
      stateStore.add(false, "isRotationLocked");
      stateStore.add(false, "isStarMode");
      stateStore.add(null, "lastUsedCatalyser");
      stateStore.add(null, "starModeTarget");
      stateStore.add(0, "versionWarns");
      stateStore.add(false, "isAutoShowPoints");
    }
  }
  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const probe = indexedDB.open(DB_NAME);
      probe.onsuccess = () => {
        const db = probe.result;
        const allCuiStores = ["config", "logs", "state", "stats", "tiles", STORE_NAME];
        if (allCuiStores.every((name) => db.objectStoreNames.contains(name))) {
          resolve(db);
          return;
        }
        const targetVersion = Math.max(db.version + 1, CUI_DB_VERSION);
        db.close();
        const upgrade = indexedDB.open(DB_NAME, targetVersion);
        upgrade.onupgradeneeded = (event) => {
          const upgradeTransaction = event.target.transaction;
          if (!upgradeTransaction) {
            reject(new Error("IDB upgrade transaction is null"));
            return;
          }
          initializeCuiDb(upgrade.result, upgradeTransaction);
        };
        upgrade.onsuccess = () => {
          resolve(upgrade.result);
        };
        upgrade.onerror = () => {
          dbPromise = null;
          reject(upgrade.error ?? new Error("IDB upgrade failed"));
        };
      };
      probe.onerror = () => {
        dbPromise = null;
        reject(probe.error ?? new Error("IDB open failed"));
      };
    });
    return dbPromise;
  }
  function isFavoriteRecord(value) {
    if (typeof value !== "object" || value === null) return false;
    const record = value;
    if (typeof record.guid !== "string") return false;
    return record.cooldown === null || typeof record.cooldown === "number";
  }
  async function loadFavorites() {
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const records = await promisifyRequest(store.getAll());
    memoryGuids = /* @__PURE__ */ new Set();
    cooldownByGuid = /* @__PURE__ */ new Map();
    for (const record of records) {
      if (!isFavoriteRecord(record)) continue;
      memoryGuids.add(record.guid);
      cooldownByGuid.set(record.guid, record.cooldown);
    }
    const seal = readSeal();
    if (memoryGuids.size === 0 && seal > 0) {
      snapshotLoaded = false;
      alert(
        t({
          en: "Favorited points data may have been lost (storage cleared). Key auto-cleanup is blocked. Re-import favorites via module settings.",
          ru: "Данные избранных точек могли быть потеряны (хранилище очищено). Автоочистка ключей заблокирована. Импортируйте избранные через настройки модуля."
        })
      );
      return;
    }
    snapshotLoaded = true;
    updateSeal();
  }
  function isFavorited(pointGuid) {
    return memoryGuids.has(pointGuid);
  }
  function getFavoritedGuids() {
    return new Set(memoryGuids);
  }
  function isFavoritesSnapshotReady() {
    return snapshotLoaded;
  }
  function getFavoritesCount() {
    return memoryGuids.size;
  }
  async function addFavorite(pointGuid) {
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    const committed = waitForTransaction(tx);
    const existing = await promisifyRequest(store.get(pointGuid));
    const cooldown = isFavoriteRecord(existing) ? existing.cooldown : null;
    const record = { guid: pointGuid, cooldown };
    await promisifyRequest(store.put(record));
    await committed;
    memoryGuids.add(pointGuid);
    cooldownByGuid.set(pointGuid, cooldown);
    updateSeal();
    emitChange();
  }
  async function removeFavorite(pointGuid) {
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    const committed = waitForTransaction(tx);
    await promisifyRequest(store.delete(pointGuid));
    await committed;
    memoryGuids.delete(pointGuid);
    cooldownByGuid.delete(pointGuid);
    updateSeal();
    emitChange();
  }
  async function exportToJson() {
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, "readonly");
    const store = tx.objectStore(STORE_NAME);
    const records = await promisifyRequest(store.getAll());
    const guids = records.filter(isFavoriteRecord).map((record) => record.guid).sort();
    return JSON.stringify(guids, null, 2);
  }
  function isGuidArray(value) {
    return Array.isArray(value) && value.every((item) => typeof item === "string");
  }
  async function importFromJson(json) {
    const parsed = JSON.parse(json);
    if (!isGuidArray(parsed)) {
      throw new Error("Некорректный формат JSON: ожидается массив GUID-строк");
    }
    const db = await openDb();
    const tx = db.transaction(STORE_NAME, "readwrite");
    const store = tx.objectStore(STORE_NAME);
    const committed = waitForTransaction(tx);
    await promisifyRequest(store.clear());
    for (const guid of parsed) {
      const record = { guid, cooldown: null };
      await promisifyRequest(store.put(record));
    }
    await committed;
    memoryGuids = new Set(parsed);
    cooldownByGuid = new Map(parsed.map((guid) => [guid, null]));
    updateSeal();
    emitChange();
    return parsed.length;
  }
  function parseInventoryCache() {
    return readInventoryCache().filter(isInventoryItem);
  }
  const TYPE_LABELS = {
    [ITEM_TYPE_CORE]: { en: "Co", ru: "Я" },
    [ITEM_TYPE_CATALYSER]: { en: "Ca", ru: "К" },
    [ITEM_TYPE_REFERENCE]: { en: "Ref", ru: "Кл" }
  };
  function shouldRunCleanup(currentCount, inventoryLimit, minFreeSlots) {
    return inventoryLimit - currentCount < minFreeSlots;
  }
  function calculateDeletions(items, limits, options) {
    const deletions = [];
    const coresByLevel = groupByLevel(items, ITEM_TYPE_CORE);
    addLevelDeletions(coresByLevel, limits.cores, ITEM_TYPE_CORE, deletions);
    const catalysersByLevel = groupByLevel(items, ITEM_TYPE_CATALYSER);
    addLevelDeletions(catalysersByLevel, limits.catalysers, ITEM_TYPE_CATALYSER, deletions);
    if (options.referencesEnabled && limits.referencesMode === "fast" && limits.referencesFastLimit !== -1 && options.favoritesSnapshotReady && options.favoritedGuids.size > 0) {
      addReferenceDeletions(items, limits.referencesFastLimit, options.favoritedGuids, deletions);
    }
    return deletions;
  }
  function groupByLevel(items, type) {
    const grouped = /* @__PURE__ */ new Map();
    for (const item of items) {
      if (item.t !== type) continue;
      if (item.a <= 0) continue;
      if (typeof item.l !== "number") continue;
      const entries2 = grouped.get(item.l) ?? [];
      entries2.push({ guid: item.g, amount: item.a });
      grouped.set(item.l, entries2);
    }
    return grouped;
  }
  function addLevelDeletions(grouped, levelLimits, type, deletions) {
    for (const [level, entries2] of grouped) {
      const limit = levelLimits[level] ?? -1;
      if (limit === -1) continue;
      const total = entries2.reduce((sum, entry) => sum + entry.amount, 0);
      let excess = total - limit;
      if (excess <= 0) continue;
      for (const entry of entries2) {
        if (excess <= 0) break;
        const toDelete = Math.min(entry.amount, excess);
        deletions.push({ guid: entry.guid, type, level, amount: toDelete });
        excess -= toDelete;
      }
    }
  }
  function addReferenceDeletions(items, limit, favoritedGuids, deletions) {
    if (limit === -1) return;
    const matching = items.filter(
      (item) => isInventoryReference(item) && item.a > 0 && !favoritedGuids.has(item.l)
    );
    const byPoint = /* @__PURE__ */ new Map();
    for (const item of matching) {
      const pointGuid = item.l;
      const entries2 = byPoint.get(pointGuid) ?? [];
      entries2.push({ guid: item.g, amount: item.a });
      byPoint.set(pointGuid, entries2);
    }
    for (const [pointGuid, entries2] of byPoint) {
      const total = entries2.reduce((sum, entry) => sum + entry.amount, 0);
      let excess = total - limit;
      if (excess <= 0) continue;
      for (const entry of entries2) {
        if (excess <= 0) break;
        const toDelete = Math.min(entry.amount, excess);
        deletions.push({
          guid: entry.guid,
          type: ITEM_TYPE_REFERENCE,
          level: null,
          amount: toDelete,
          pointGuid
        });
        excess -= toDelete;
      }
    }
  }
  function formatDeletionSummary(deletions) {
    const grouped = /* @__PURE__ */ new Map();
    for (const entry of deletions) {
      const localizedLabel = TYPE_LABELS[entry.type];
      const label = localizedLabel ? t(localizedLabel) : `?${entry.type}`;
      const key = entry.level !== null ? `${label}${entry.level}` : label;
      grouped.set(key, (grouped.get(key) ?? 0) + entry.amount);
    }
    const parts = [];
    for (const [label, amount] of grouped) {
      parts.push(`${label} ×${amount}`);
    }
    return parts.join(", ");
  }
  const STORAGE_KEY$1 = "svp_inventoryCleanup";
  const MIN_FREE_SLOTS_FLOOR = 20;
  const CURRENT_VERSION = 2;
  function defaultLevelLimits() {
    const limits = {};
    for (let level = 1; level <= 10; level++) {
      limits[level] = -1;
    }
    return limits;
  }
  function defaultCleanupSettings() {
    return {
      version: CURRENT_VERSION,
      limits: {
        cores: defaultLevelLimits(),
        catalysers: defaultLevelLimits(),
        referencesMode: "off",
        referencesFastLimit: -1,
        referencesAlliedLimit: -1,
        referencesNotAlliedLimit: -1
      },
      minFreeSlots: 100
    };
  }
  function isRecord(value) {
    return typeof value === "object" && value !== null;
  }
  function isLevelLimits(value) {
    if (!isRecord(value)) return false;
    for (let level = 1; level <= 10; level++) {
      if (typeof value[level] !== "number") return false;
    }
    return true;
  }
  function isReferencesMode(value) {
    return value === "off" || value === "fast" || value === "slow";
  }
  function isCleanupLimitsV2(value) {
    if (!isRecord(value)) return false;
    return isLevelLimits(value.cores) && isLevelLimits(value.catalysers) && isReferencesMode(value.referencesMode) && typeof value.referencesFastLimit === "number" && typeof value.referencesAlliedLimit === "number" && typeof value.referencesNotAlliedLimit === "number";
  }
  function isCleanupSettingsV2(value) {
    if (!isRecord(value)) return false;
    return typeof value.version === "number" && isCleanupLimitsV2(value.limits) && typeof value.minFreeSlots === "number";
  }
  function isCleanupLimitsV1(value) {
    if (!isRecord(value)) return false;
    return isLevelLimits(value.cores) && isLevelLimits(value.catalysers) && typeof value.references === "number";
  }
  function isCleanupSettingsV1(value) {
    if (!isRecord(value)) return false;
    return typeof value.version === "number" && value.version === 1 && isCleanupLimitsV1(value.limits) && typeof value.minFreeSlots === "number";
  }
  function migrateV1ToV2(v1) {
    const { references } = v1.limits;
    const mode = references === -1 ? "off" : "fast";
    return {
      version: 2,
      limits: {
        cores: v1.limits.cores,
        catalysers: v1.limits.catalysers,
        referencesMode: mode,
        referencesFastLimit: references,
        referencesAlliedLimit: -1,
        referencesNotAlliedLimit: -1
      },
      minFreeSlots: v1.minFreeSlots
    };
  }
  function loadCleanupSettings() {
    const raw = localStorage.getItem(STORAGE_KEY$1);
    if (!raw) return defaultCleanupSettings();
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return defaultCleanupSettings();
    }
    if (isCleanupSettingsV1(parsed)) {
      const migrated = migrateV1ToV2(parsed);
      saveCleanupSettings(migrated);
      return applyRuntimeGuards(migrated);
    }
    if (!isCleanupSettingsV2(parsed)) return defaultCleanupSettings();
    return applyRuntimeGuards(parsed);
  }
  function applyRuntimeGuards(settings) {
    if (settings.minFreeSlots < MIN_FREE_SLOTS_FLOOR) {
      settings.minFreeSlots = MIN_FREE_SLOTS_FLOOR;
    }
    sanitizeLimits(settings.limits);
    return settings;
  }
  function sanitizeLevelLimits(limits) {
    for (let level = 1; level <= 10; level++) {
      if (limits[level] < -1) {
        limits[level] = 0;
      }
    }
  }
  function sanitizeRefLimit(value) {
    return value < -1 ? 0 : value;
  }
  function sanitizeLimits(limits) {
    sanitizeLevelLimits(limits.cores);
    sanitizeLevelLimits(limits.catalysers);
    limits.referencesFastLimit = sanitizeRefLimit(limits.referencesFastLimit);
    limits.referencesAlliedLimit = sanitizeRefLimit(limits.referencesAlliedLimit);
    limits.referencesNotAlliedLimit = sanitizeRefLimit(limits.referencesNotAlliedLimit);
  }
  function saveCleanupSettings(settings) {
    localStorage.setItem(STORAGE_KEY$1, JSON.stringify(settings));
  }
  const styles$1 = ".svp-cleanup-settings{position:fixed;top:0;right:0;bottom:0;left:0;z-index:10001;background:var(--background);color:var(--text);display:none;flex-direction:column;font-size:13px}.svp-cleanup-settings.svp-open{display:flex}.svp-cleanup-header{display:flex;justify-content:space-between;align-items:center;font-size:14px;font-weight:700;padding:4px 8px;flex-shrink:0;border-bottom:1px solid var(--border-transp);max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-content{flex:1;overflow-y:auto;padding:8px;display:flex;flex-direction:column;gap:8px;max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-section-title{font-size:10px;font-weight:600;color:var(--text);text-transform:uppercase;letter-spacing:.08em;padding:6px 0 2px;border-bottom:1px solid var(--border-transp);margin-bottom:2px}.svp-cleanup-level-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px 12px}.svp-cleanup-level-cell,.svp-cleanup-row{display:flex;justify-content:space-between;align-items:center;padding:2px 0}.svp-cleanup-row-label{font-size:12px}.svp-cleanup-row-input{width:60px;padding:2px 4px;border:1px solid var(--border);border-radius:4px;background:var(--background);color:var(--text);font-size:12px;text-align:center}.svp-cleanup-references-section{margin-top:8px;padding-top:8px;border-top:1px solid var(--border-transp)}.svp-cleanup-radio-label{display:block;font-size:12px;margin:4px 0;cursor:pointer;-webkit-user-select:none;user-select:none}.svp-cleanup-ref-inputs{margin-top:4px;padding-left:20px}.svp-cleanup-hint{font-size:10px;color:var(--text-disabled);padding:4px 0}.svp-cleanup-hint-warning{color:var(--accent)}.svp-cleanup-slow-refs-button{margin-left:auto;padding:4px 10px;border:1px solid var(--border, rgba(255, 255, 255, .2));border-radius:4px;background:var(--background, rgba(0, 0, 0, .3));color:var(--text, #ddd);font-size:11px;cursor:pointer}.svp-cleanup-slow-refs-button:hover{background:var(--accent, #ffcc33);color:var(--background, #000)}.svp-cleanup-slow-modal{position:fixed;top:0;right:0;bottom:0;left:0;z-index:10010;background:#000000b3;display:flex;align-items:center;justify-content:center}.svp-cleanup-slow-modal-box{background:var(--background, #1a1a1a);color:var(--text, #ddd);border:1px solid var(--border, #444);border-radius:6px;padding:16px 20px;min-width:260px;max-width:400px}.svp-cleanup-slow-modal-status{font-size:13px;margin-bottom:8px}.svp-cleanup-slow-progress{height:6px;background:#ffffff1a;border-radius:3px;overflow:hidden}.svp-cleanup-slow-progress-bar{height:100%;width:0;background:#fc3;transition:width .15s ease}.svp-cleanup-slow-counter{font-size:11px;color:var(--text-disabled, #888);text-align:right;margin-top:4px}.svp-cleanup-footer{flex-shrink:0;padding:6px 8px 40px;border-top:1px solid var(--border-transp);display:flex;align-items:center;justify-content:flex-end;gap:8px;max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-button{background:none;border:1px solid var(--border);color:var(--text);border-radius:4px;padding:4px 12px;font-size:12px;cursor:pointer}.svp-cleanup-button-primary{background:var(--accent);color:var(--background);border-color:var(--accent)}.svp-cleanup-configure-button{background:none;border:1px solid var(--border);color:var(--text);border-radius:4px;padding:2px 6px;font-size:10px;cursor:pointer;margin-left:4px}";
  const STYLES_ID = "inventoryCleanup";
  const PANEL_ID = "svp-cleanup-settings";
  const TITLE = {
    en: "Inventory cleanup settings",
    ru: "Настройки очистки инвентаря"
  };
  const SAVE_LABEL = { en: "Save", ru: "Сохранить" };
  const CANCEL_LABEL = { en: "Cancel", ru: "Отмена" };
  const CONFIGURE_LABEL = { en: "Configure", ru: "Настроить" };
  const MIN_FREE_SLOTS_LABEL = {
    en: "Min free slots",
    ru: "Мин. свободных слотов"
  };
  const CORES_LABEL = { en: "Cores", ru: "Ядра" };
  const CATALYSERS_LABEL = { en: "Catalysers", ru: "Катализаторы" };
  const LEVEL_LABEL = { en: "Level", ru: "Ур." };
  const UNLIMITED_HINT = { en: "-1 = unlimited", ru: "-1 = без лимита" };
  const REFERENCES_LABEL = { en: "Keys", ru: "Ключи" };
  const REF_MODE_OFF_LABEL = { en: "Off", ru: "Не удалять" };
  const REF_MODE_FAST_LABEL = {
    en: "Fast (on discover)",
    ru: "Быстро (при изучении)"
  };
  const REF_MODE_SLOW_LABEL = {
    en: "Slow (allied/not allied split, manual only)",
    ru: "Медленно (союзные/несоюзные, только вручную)"
  };
  const REF_FAST_LIMIT_LABEL = {
    en: "Keys limit",
    ru: "Лимит ключей"
  };
  const REF_ALLIED_LIMIT_LABEL = {
    en: "Allied keys limit",
    ru: "Лимит союзных"
  };
  const REF_NOT_ALLIED_LIMIT_LABEL = {
    en: "Not allied keys limit",
    ru: "Лимит несоюзных"
  };
  const REF_DISABLED_HINT = {
    en: 'Enable "Favorited points" module to manage key deletion',
    ru: "Включите модуль «Избранные точки», чтобы настроить удаление ключей"
  };
  const REF_SLOW_HINT = {
    en: "Slow cleanup runs manually from the references OPS tab",
    ru: "Медленная очистка запускается вручную через кнопку во вкладке ключей в ОРПЦ"
  };
  let panel$1 = null;
  let configureButton$1 = null;
  let moduleRowObserver$1 = null;
  let rafId$2 = null;
  function createLevelInputs(container, titleLabel, values, onChange) {
    const section = document.createElement("div");
    const sectionTitle = document.createElement("div");
    sectionTitle.className = "svp-cleanup-section-title";
    sectionTitle.textContent = t(titleLabel);
    section.appendChild(sectionTitle);
    const grid = document.createElement("div");
    grid.className = "svp-cleanup-level-grid";
    for (let level = 1; level <= 10; level++) {
      const cell = document.createElement("div");
      cell.className = "svp-cleanup-level-cell";
      const label = document.createElement("span");
      label.className = "svp-cleanup-row-label";
      label.textContent = `${t(LEVEL_LABEL)} ${level}`;
      const input = document.createElement("input");
      input.type = "number";
      input.className = "svp-cleanup-row-input";
      input.min = "-1";
      input.value = String(values[level] ?? -1);
      input.addEventListener("change", () => {
        const parsed = parseInt(input.value, 10);
        const clamped = Number.isFinite(parsed) && parsed >= 0 ? parsed : -1;
        input.value = String(clamped);
        onChange(level, clamped);
      });
      cell.appendChild(label);
      cell.appendChild(input);
      grid.appendChild(cell);
    }
    section.appendChild(grid);
    container.appendChild(section);
  }
  function createNumberInput(labelText, value, onChange) {
    const row = document.createElement("div");
    row.className = "svp-cleanup-row";
    const label = document.createElement("span");
    label.className = "svp-cleanup-row-label";
    label.textContent = labelText;
    const input = document.createElement("input");
    input.type = "number";
    input.className = "svp-cleanup-row-input";
    input.min = "-1";
    input.value = String(value);
    input.addEventListener("change", () => {
      const parsed = parseInt(input.value, 10);
      const clamped = Number.isFinite(parsed) && parsed >= 0 ? parsed : -1;
      input.value = String(clamped);
      onChange(clamped);
    });
    row.appendChild(label);
    row.appendChild(input);
    return row;
  }
  function createReferencesSection(draft, refsEnabled) {
    const section = document.createElement("div");
    section.className = "svp-cleanup-references-section";
    const title = document.createElement("div");
    title.className = "svp-cleanup-section-title";
    title.textContent = t(REFERENCES_LABEL);
    section.appendChild(title);
    if (!refsEnabled) {
      const hint = document.createElement("div");
      hint.className = "svp-cleanup-hint svp-cleanup-hint-warning";
      hint.textContent = t(REF_DISABLED_HINT);
      section.appendChild(hint);
      return section;
    }
    const modeGroupName = "svp-cleanup-ref-mode";
    const modes = [
      { value: "off", label: REF_MODE_OFF_LABEL },
      { value: "fast", label: REF_MODE_FAST_LABEL },
      { value: "slow", label: REF_MODE_SLOW_LABEL }
    ];
    const inputsContainer = document.createElement("div");
    inputsContainer.className = "svp-cleanup-ref-inputs";
    function renderInputs() {
      inputsContainer.innerHTML = "";
      if (draft.limits.referencesMode === "fast") {
        inputsContainer.appendChild(
          createNumberInput(t(REF_FAST_LIMIT_LABEL), draft.limits.referencesFastLimit, (value) => {
            draft.limits.referencesFastLimit = value;
          })
        );
      } else if (draft.limits.referencesMode === "slow") {
        inputsContainer.appendChild(
          createNumberInput(
            t(REF_ALLIED_LIMIT_LABEL),
            draft.limits.referencesAlliedLimit,
            (value) => {
              draft.limits.referencesAlliedLimit = value;
            }
          )
        );
        inputsContainer.appendChild(
          createNumberInput(
            t(REF_NOT_ALLIED_LIMIT_LABEL),
            draft.limits.referencesNotAlliedLimit,
            (value) => {
              draft.limits.referencesNotAlliedLimit = value;
            }
          )
        );
        const slowHint = document.createElement("div");
        slowHint.className = "svp-cleanup-hint";
        slowHint.textContent = t(REF_SLOW_HINT);
        inputsContainer.appendChild(slowHint);
      }
    }
    for (const mode of modes) {
      const label = document.createElement("label");
      label.className = "svp-cleanup-radio-label";
      const radio = document.createElement("input");
      radio.type = "radio";
      radio.name = modeGroupName;
      radio.value = mode.value;
      radio.checked = draft.limits.referencesMode === mode.value;
      radio.addEventListener("change", () => {
        if (radio.checked) {
          draft.limits.referencesMode = mode.value;
          renderInputs();
        }
      });
      label.appendChild(radio);
      label.appendChild(document.createTextNode(" " + t(mode.label)));
      section.appendChild(label);
    }
    section.appendChild(inputsContainer);
    renderInputs();
    return section;
  }
  function buildPanel$1(settings, onSave) {
    const draft = structuredClone(settings);
    const element = document.createElement("div");
    element.className = "svp-cleanup-settings";
    element.id = PANEL_ID;
    const header = document.createElement("div");
    header.className = "svp-cleanup-header";
    header.textContent = t(TITLE);
    element.appendChild(header);
    const content = document.createElement("div");
    content.className = "svp-cleanup-content";
    const hint = document.createElement("div");
    hint.style.fontSize = "10px";
    hint.style.color = "var(--text-disabled)";
    hint.textContent = t(UNLIMITED_HINT);
    content.appendChild(hint);
    const minFreeSlotsRow = document.createElement("div");
    minFreeSlotsRow.className = "svp-cleanup-row";
    const minFreeSlotsLabel = document.createElement("span");
    minFreeSlotsLabel.className = "svp-cleanup-row-label";
    minFreeSlotsLabel.textContent = t(MIN_FREE_SLOTS_LABEL);
    const minFreeSlotsInput = document.createElement("input");
    minFreeSlotsInput.type = "number";
    minFreeSlotsInput.className = "svp-cleanup-row-input";
    minFreeSlotsInput.min = "20";
    minFreeSlotsInput.value = String(draft.minFreeSlots);
    minFreeSlotsInput.addEventListener("change", () => {
      draft.minFreeSlots = Math.max(20, parseInt(minFreeSlotsInput.value, 10) || 20);
      minFreeSlotsInput.value = String(draft.minFreeSlots);
    });
    minFreeSlotsRow.appendChild(minFreeSlotsLabel);
    minFreeSlotsRow.appendChild(minFreeSlotsInput);
    content.appendChild(minFreeSlotsRow);
    createLevelInputs(content, CORES_LABEL, draft.limits.cores, (level, value) => {
      draft.limits.cores[level] = value;
    });
    createLevelInputs(content, CATALYSERS_LABEL, draft.limits.catalysers, (level, value) => {
      draft.limits.catalysers[level] = value;
    });
    const refsEnabled = isModuleActive("favoritedPoints");
    content.appendChild(createReferencesSection(draft, refsEnabled));
    element.appendChild(content);
    const footer = document.createElement("div");
    footer.className = "svp-cleanup-footer";
    const cancelButton = document.createElement("button");
    cancelButton.className = "svp-cleanup-button";
    cancelButton.textContent = t(CANCEL_LABEL);
    cancelButton.addEventListener("click", () => {
      element.classList.remove("svp-open");
    });
    const saveButton = document.createElement("button");
    saveButton.className = "svp-cleanup-button svp-cleanup-button-primary";
    saveButton.textContent = t(SAVE_LABEL);
    saveButton.addEventListener("click", () => {
      onSave(draft);
      element.classList.remove("svp-open");
    });
    footer.appendChild(cancelButton);
    footer.appendChild(saveButton);
    element.appendChild(footer);
    return element;
  }
  function injectConfigureButton$1() {
    const moduleRow = document.querySelector(".svp-module-row .svp-module-id");
    if (!moduleRow) return;
    const allIds = document.querySelectorAll(".svp-module-id");
    for (const idElement of allIds) {
      if (idElement.textContent === "inventoryCleanup") {
        const row = idElement.closest(".svp-module-row");
        if (!row) continue;
        const existing = row.querySelector(".svp-cleanup-configure-button");
        if (existing) return;
        const nameLine = row.querySelector(".svp-module-name-line");
        if (!nameLine) continue;
        configureButton$1 = document.createElement("button");
        configureButton$1.className = "svp-cleanup-configure-button";
        configureButton$1.textContent = t(CONFIGURE_LABEL);
        configureButton$1.addEventListener("click", (event) => {
          event.stopPropagation();
          openSettingsPanel();
        });
        nameLine.appendChild(configureButton$1);
        return;
      }
    }
  }
  function openSettingsPanel() {
    if (panel$1) {
      panel$1.remove();
    }
    const settings = loadCleanupSettings();
    panel$1 = buildPanel$1(settings, (updatedSettings) => {
      saveCleanupSettings(updatedSettings);
    });
    document.body.appendChild(panel$1);
    panel$1.classList.add("svp-open");
  }
  function initCleanupSettingsUi() {
    injectStyles(styles$1, STYLES_ID);
    injectConfigureButton$1();
    moduleRowObserver$1 = new MutationObserver(() => {
      if (rafId$2 !== null) return;
      rafId$2 = requestAnimationFrame(() => {
        rafId$2 = null;
        if (!document.querySelector(".svp-cleanup-configure-button")) {
          injectConfigureButton$1();
        }
      });
    });
    moduleRowObserver$1.observe(document.body, { childList: true, subtree: true });
  }
  function destroyCleanupSettingsUi() {
    if (rafId$2 !== null) {
      cancelAnimationFrame(rafId$2);
      rafId$2 = null;
    }
    removeStyles(STYLES_ID);
    if (panel$1) {
      panel$1.remove();
      panel$1 = null;
    }
    if (configureButton$1) {
      configureButton$1.remove();
      configureButton$1 = null;
    }
    if (moduleRowObserver$1) {
      moduleRowObserver$1.disconnect();
      moduleRowObserver$1 = null;
    }
  }
  function buildAuthHeaders() {
    const token = localStorage.getItem("auth");
    if (!token) {
      throw new Error("Auth token not found");
    }
    return {
      authorization: `Bearer ${token}`,
      "content-type": "application/json"
    };
  }
  function groupByType(deletions) {
    const grouped = /* @__PURE__ */ new Map();
    for (const entry of deletions) {
      let selection = grouped.get(entry.type);
      if (!selection) {
        selection = {};
        grouped.set(entry.type, selection);
      }
      selection[entry.guid] = (selection[entry.guid] ?? 0) + entry.amount;
    }
    return grouped;
  }
  const DELETABLE_TYPES = /* @__PURE__ */ new Set([ITEM_TYPE_CORE, ITEM_TYPE_CATALYSER, ITEM_TYPE_REFERENCE]);
  async function deleteInventoryItems(deletions, options) {
    const hasReferences = deletions.some((entry) => entry.type === ITEM_TYPE_REFERENCE);
    if (hasReferences && !options.favoritedPointsActive) {
      throw new Error("Удаление ключей запрещено: модуль favoritedPoints не активен (guard)");
    }
    for (const entry of deletions) {
      if (!DELETABLE_TYPES.has(entry.type)) {
        throw new Error(`Удаление предметов типа ${entry.type} запрещено`);
      }
      if (entry.type === ITEM_TYPE_REFERENCE) {
        if (entry.pointGuid === void 0) {
          throw new Error(`Ключ ${entry.guid} без pointGuid не может быть удалён (guard избранных)`);
        }
        if (options.favoritedGuids.has(entry.pointGuid)) {
          throw new Error(
            `Ключ от избранной точки ${entry.pointGuid} не может быть удалён (guard избранных)`
          );
        }
      }
    }
    const grouped = groupByType(deletions);
    let lastTotal = 0;
    for (const [type, selection] of grouped) {
      const response = await fetch("/api/inventory", {
        method: "DELETE",
        headers: buildAuthHeaders(),
        body: JSON.stringify({ selection, tab: type })
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      let parsed;
      try {
        parsed = await response.json();
      } catch {
        throw new Error("Invalid response from server");
      }
      if (parsed.error) {
        throw new Error(parsed.error);
      }
      if (!parsed.count || typeof parsed.count.total !== "number") {
        throw new Error("Response missing inventory count");
      }
      lastTotal = parsed.count.total;
    }
    return { total: lastTotal };
  }
  function updateDomInventoryCount(total) {
    const element = document.getElementById("self-info__inv");
    if (element) {
      element.textContent = String(total);
    }
  }
  function updatePointRefCount() {
    const infoPopup = document.querySelector(".info.popup");
    if (!infoPopup || infoPopup.classList.contains("hidden")) return;
    const pointGuid = infoPopup.dataset.guid;
    if (!pointGuid) return;
    const refElement = document.getElementById("i-ref");
    if (!refElement) return;
    const raw = localStorage.getItem(INVENTORY_CACHE_KEY);
    if (!raw) return;
    let cache;
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) return;
      cache = parsed;
    } catch {
      return;
    }
    const ref = cache.find(
      (item) => typeof item === "object" && item !== null && item.t === ITEM_TYPE_REFERENCE && item.l === pointGuid
    );
    const count = (ref == null ? void 0 : ref.a) ?? 0;
    const currentText = refElement.textContent;
    const updatedText = currentText.replace(/\d+(?=\/)/, String(count));
    if (updatedText !== currentText) {
      refElement.textContent = updatedText;
    }
    refElement.setAttribute("data-has", count > 0 ? "1" : "0");
  }
  function updateInventoryCache(deletions) {
    const raw = localStorage.getItem(INVENTORY_CACHE_KEY);
    if (!raw) {
      console.warn("[SVP inventoryCleanup] inventory-cache отсутствует, пропуск обновления");
      return;
    }
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      console.warn("[SVP inventoryCleanup] inventory-cache содержит невалидный JSON");
      return;
    }
    if (!Array.isArray(parsed)) {
      console.warn("[SVP inventoryCleanup] inventory-cache не является массивом");
      return;
    }
    let cache = parsed;
    for (const entry of deletions) {
      const cached = cache.find((item) => item.g === entry.guid);
      if (cached) {
        cached.a -= entry.amount;
      }
    }
    cache = cache.filter((item) => item.a > 0);
    localStorage.setItem(INVENTORY_CACHE_KEY, JSON.stringify(cache));
  }
  const BUTTON_CLASS = "svp-cleanup-slow-refs-button";
  const MODAL_CLASS = "svp-cleanup-slow-modal";
  const FILTER_BAR_SELECTOR = ".svp-fav-filter-bar";
  const FETCH_CONCURRENCY = 3;
  let bodyObserver = null;
  function getPlayerTeam() {
    const element = document.getElementById("self-info__name");
    if (!element) return null;
    const match = /var\(--team-(\d+)\)/.exec(element.style.color);
    if (!match) return null;
    const team = parseInt(match[1], 10);
    return Number.isFinite(team) ? team : null;
  }
  async function fetchPointTeam(pointGuid) {
    try {
      const response = await fetch(`/api/point?guid=${pointGuid}&status=1`);
      if (!response.ok) return null;
      const json = await response.json();
      if (typeof json !== "object" || json === null || !("data" in json)) return null;
      const record = json;
      const data = record.data;
      if (typeof data !== "object" || data === null) return null;
      const dataRecord = data;
      if (typeof dataRecord.te === "number") return dataRecord.te;
      return null;
    } catch {
      return null;
    }
  }
  function openProgressModal() {
    const overlay = document.createElement("div");
    overlay.className = MODAL_CLASS;
    const box = document.createElement("div");
    box.className = "svp-cleanup-slow-modal-box";
    const status = document.createElement("div");
    status.className = "svp-cleanup-slow-modal-status";
    status.textContent = t({ en: "Preparing…", ru: "Подготовка…" });
    box.appendChild(status);
    const barWrap = document.createElement("div");
    barWrap.className = "svp-cleanup-slow-progress";
    const bar = document.createElement("div");
    bar.className = "svp-cleanup-slow-progress-bar";
    barWrap.appendChild(bar);
    box.appendChild(barWrap);
    const counter = document.createElement("div");
    counter.className = "svp-cleanup-slow-counter";
    counter.textContent = "0 / 0";
    box.appendChild(counter);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    return {
      update(done, total) {
        const percent = total === 0 ? 0 : Math.round(done / total * 100);
        bar.style.width = `${percent}%`;
        counter.textContent = `${done} / ${total}`;
      },
      setStatus(text) {
        status.textContent = text;
      },
      close() {
        overlay.remove();
      }
    };
  }
  async function fetchTeamsForGuids(guids, onProgress) {
    const result = /* @__PURE__ */ new Map();
    let done = 0;
    let cursor = 0;
    async function worker() {
      while (cursor < guids.length) {
        const index = cursor++;
        const guid = guids[index];
        const team = await fetchPointTeam(guid);
        result.set(guid, team);
        done++;
        onProgress(done, guids.length);
      }
    }
    const workers = [];
    for (let i = 0; i < Math.min(FETCH_CONCURRENCY, guids.length); i++) {
      workers.push(worker());
    }
    await Promise.all(workers);
    return result;
  }
  function calculateSlowDeletions(refs, teams, playerTeam, alliedLimit, notAlliedLimit) {
    const alliedRefs = [];
    const notAlliedRefs = [];
    for (const ref of refs) {
      const team = teams.get(ref.pointGuid);
      if (team === playerTeam) {
        alliedRefs.push(ref);
      } else {
        notAlliedRefs.push(ref);
      }
    }
    const deletions = [];
    collectOverLimit(alliedRefs, alliedLimit, deletions);
    collectOverLimit(notAlliedRefs, notAlliedLimit, deletions);
    return deletions;
  }
  function collectOverLimit(refs, limit, deletions) {
    if (limit === -1) return;
    const byPoint = /* @__PURE__ */ new Map();
    for (const ref of refs) {
      const group = byPoint.get(ref.pointGuid) ?? [];
      group.push(ref);
      byPoint.set(ref.pointGuid, group);
    }
    for (const [pointGuid, group] of byPoint) {
      const total = group.reduce((sum, ref) => sum + ref.amount, 0);
      let excess = total - limit;
      if (excess <= 0) continue;
      for (const ref of group) {
        if (excess <= 0) break;
        const toDelete = Math.min(ref.amount, excess);
        deletions.push({
          guid: ref.itemGuid,
          type: ITEM_TYPE_REFERENCE,
          level: null,
          amount: toDelete,
          pointGuid
        });
        excess -= toDelete;
      }
    }
  }
  const SLOW_TOAST_DURATION = 5e3;
  function showSlowToast(message) {
    showToast(message, SLOW_TOAST_DURATION);
  }
  async function runSlowDelete() {
    const settings = loadCleanupSettings();
    if (settings.limits.referencesMode !== "slow") {
      showSlowToast(
        t({ en: 'Key cleanup mode is not "Slow"', ru: "Режим очистки ключей не «Медленно»" })
      );
      return;
    }
    const { referencesAlliedLimit, referencesNotAlliedLimit } = settings.limits;
    if (referencesAlliedLimit === -1 && referencesNotAlliedLimit === -1) {
      showSlowToast(t({ en: "Limits not set", ru: "Лимиты не заданы" }));
      return;
    }
    const playerTeam = getPlayerTeam();
    if (playerTeam === null) {
      showSlowToast(
        t({ en: "Could not determine player team", ru: "Не удалось определить команду игрока" })
      );
      return;
    }
    const favoritedGuids = getFavoritedGuids();
    if (favoritedGuids.size === 0) {
      showSlowToast(
        t({
          en: "Add at least one favorited point before cleaning keys",
          ru: "Добавьте хотя бы одну избранную точку перед очисткой ключей"
        })
      );
      return;
    }
    const invRefs = readInventoryReferences();
    const nonFavRefs = invRefs.filter((ref) => ref.a > 0 && !favoritedGuids.has(ref.l)).map((ref) => ({ itemGuid: ref.g, pointGuid: ref.l, amount: ref.a }));
    if (nonFavRefs.length === 0) {
      showSlowToast(
        t({ en: "No non-favorited keys to process", ru: "Нет не-избранных ключей для обработки" })
      );
      return;
    }
    const uniquePointGuids = Array.from(new Set(nonFavRefs.map((ref) => ref.pointGuid)));
    const confirmed = confirm(
      t({
        en: `Fetch data for ${uniquePointGuids.length} points to determine faction? This may take a while.`,
        ru: `Запросить данные по ${uniquePointGuids.length} точкам для определения фракции? Это может занять время.`
      })
    );
    if (!confirmed) return;
    const progress = openProgressModal();
    progress.setStatus(t({ en: "Fetching point data…", ru: "Запрос данных точек…" }));
    progress.update(0, uniquePointGuids.length);
    let teams;
    try {
      teams = await fetchTeamsForGuids(uniquePointGuids, (done, total) => {
        progress.update(done, total);
      });
    } catch (error) {
      progress.close();
      const message = error instanceof Error ? error.message : "Неизвестная ошибка";
      showSlowToast(t({ en: "Request error: ", ru: "Ошибка запроса: " }) + message);
      return;
    }
    progress.setStatus(t({ en: "Calculating deletions…", ru: "Расчёт удаления…" }));
    const deletions = calculateSlowDeletions(
      nonFavRefs,
      teams,
      playerTeam,
      referencesAlliedLimit,
      referencesNotAlliedLimit
    );
    if (deletions.length === 0) {
      progress.close();
      showSlowToast(
        t({
          en: "No keys to delete with current limits",
          ru: "Нет ключей для удаления по заданным лимитам"
        })
      );
      return;
    }
    const totalAmount = deletions.reduce((sum, entry) => sum + entry.amount, 0);
    const alliedDeletions = deletions.filter((entry) => {
      const team = teams.get(entry.pointGuid ?? "");
      return team === playerTeam;
    });
    const notAlliedDeletions = deletions.filter((entry) => {
      const team = teams.get(entry.pointGuid ?? "");
      return team !== playerTeam;
    });
    const alliedAmount = alliedDeletions.reduce((sum, entry) => sum + entry.amount, 0);
    const notAlliedAmount = notAlliedDeletions.reduce((sum, entry) => sum + entry.amount, 0);
    const alliedLabel = t({ en: "allied", ru: "союзные" });
    const notAlliedLabel = t({ en: "not allied", ru: "несоюзные" });
    const keysLabel = t({ en: "keys", ru: "ключей" });
    const summaryText = `${totalAmount} ${keysLabel} (${alliedLabel} ${alliedAmount} + ${notAlliedLabel} ${notAlliedAmount})`;
    progress.setStatus(t({ en: "Deleting: ", ru: "Удаление: " }) + summaryText);
    try {
      const result = await deleteInventoryItems(deletions, {
        favoritedGuids: getFavoritedGuids(),
        favoritedPointsActive: isModuleActive("favoritedPoints")
      });
      updateInventoryCache(deletions);
      updatePointRefCount();
      if (result.total > 0) {
        updateDomInventoryCount(result.total);
      }
      progress.close();
      showSlowToast(t({ en: "Deleted: ", ru: "Удалено: " }) + summaryText);
    } catch (error) {
      progress.close();
      const message = error instanceof Error ? error.message : t({ en: "Unknown error", ru: "Неизвестная ошибка" });
      showSlowToast(t({ en: "Deletion error: ", ru: "Ошибка удаления: " }) + message);
    }
  }
  function formatLimit(value) {
    return value === -1 ? "∞" : String(value);
  }
  function updateButtonLabel(button) {
    const settings = loadCleanupSettings();
    const allied = formatLimit(settings.limits.referencesAlliedLimit);
    const notAllied = formatLimit(settings.limits.referencesNotAlliedLimit);
    const label = t({
      en: `Cleanup (limits: ${allied}/${notAllied})`,
      ru: `Очистить (лимиты: ${allied}/${notAllied})`
    });
    if (button.textContent !== label) {
      button.textContent = label;
    }
  }
  function shouldShowButton() {
    const settings = loadCleanupSettings();
    return settings.limits.referencesMode === "slow" && isModuleActive("favoritedPoints");
  }
  function ensureButton(bar) {
    var _a;
    if (!shouldShowButton()) {
      (_a = bar.querySelector(`.${BUTTON_CLASS}`)) == null ? void 0 : _a.remove();
      return;
    }
    const existing = bar.querySelector(`.${BUTTON_CLASS}`);
    if (existing) {
      updateButtonLabel(existing);
      return;
    }
    const button = document.createElement("button");
    button.type = "button";
    button.className = BUTTON_CLASS;
    updateButtonLabel(button);
    button.addEventListener("click", (event) => {
      event.preventDefault();
      void runSlowDelete();
    });
    bar.appendChild(button);
  }
  function removeButton() {
    var _a;
    (_a = document.querySelector(`.${BUTTON_CLASS}`)) == null ? void 0 : _a.remove();
  }
  function checkAndInject() {
    const bar = document.querySelector(FILTER_BAR_SELECTOR);
    if (!bar) {
      removeButton();
      return;
    }
    ensureButton(bar);
  }
  let rafId$1 = null;
  function installSlowRefsDelete() {
    if (bodyObserver) return;
    checkAndInject();
    bodyObserver = new MutationObserver(() => {
      if (rafId$1 !== null) return;
      rafId$1 = requestAnimationFrame(() => {
        rafId$1 = null;
        checkAndInject();
      });
    });
    bodyObserver.observe(document.body, { childList: true, subtree: true });
  }
  function uninstallSlowRefsDelete() {
    if (rafId$1 !== null) {
      cancelAnimationFrame(rafId$1);
      rafId$1 = null;
    }
    bodyObserver == null ? void 0 : bodyObserver.disconnect();
    bodyObserver = null;
    removeButton();
  }
  const MODULE_ID$2 = "inventoryCleanup";
  const ACTION_SELECTORS = "#discover, .discover-mod";
  const DEBUG_INV_KEY = "svp_debug_inv";
  let cleanupInProgress = false;
  let discoverPending = false;
  let originalSetItem = null;
  let setItemPatchTarget = null;
  function readDebugInvCount() {
    const match = /[#&]svp-inv=(\d+)/.exec(location.hash);
    if (match) {
      sessionStorage.setItem(DEBUG_INV_KEY, match[1]);
    }
    const stored = sessionStorage.getItem(DEBUG_INV_KEY);
    if (stored === null) return null;
    const value = parseInt(stored, 10);
    return Number.isFinite(value) ? value : null;
  }
  function readDomNumber(id) {
    const element = document.getElementById(id);
    if (!element) return null;
    const value = parseInt(element.textContent, 10);
    return Number.isFinite(value) ? value : null;
  }
  async function runCleanup() {
    if (cleanupInProgress) return;
    cleanupInProgress = true;
    try {
      await runCleanupImpl();
    } finally {
      cleanupInProgress = false;
    }
  }
  async function runCleanupImpl() {
    const settings = loadCleanupSettings();
    const currentCount = readDebugInvCount() ?? readDomNumber("self-info__inv");
    const inventoryLimit = readDomNumber("self-info__inv-lim");
    if (currentCount === null || inventoryLimit === null) {
      console.warn("[SVP inventoryCleanup] Не удалось прочитать инвентарь из DOM");
      return;
    }
    if (!shouldRunCleanup(currentCount, inventoryLimit, settings.minFreeSlots)) {
      return;
    }
    const items = parseInventoryCache();
    if (items.length === 0) return;
    const referencesEnabled = isModuleActive("favoritedPoints");
    const favoritedGuids = referencesEnabled ? getFavoritedGuids() : /* @__PURE__ */ new Set();
    const deletions = calculateDeletions(items, settings.limits, {
      favoritedGuids,
      referencesEnabled,
      favoritesSnapshotReady: isFavoritesSnapshotReady()
    });
    if (deletions.length === 0) return;
    const totalAmount = deletions.reduce((sum, entry) => sum + entry.amount, 0);
    const summary = formatDeletionSummary(deletions);
    console.log(
      `[SVP inventoryCleanup] Удалить ${totalAmount} предметов (инвентарь: ${currentCount}/${inventoryLimit})`,
      deletions
    );
    try {
      const result = await deleteInventoryItems(deletions, {
        favoritedGuids: getFavoritedGuids(),
        favoritedPointsActive: isModuleActive("favoritedPoints")
      });
      updateInventoryCache(deletions);
      updatePointRefCount();
      if (result.total > 0) {
        updateDomInventoryCount(result.total);
      }
      showToast(`Очистка (${totalAmount}): ${summary}`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Неизвестная ошибка";
      console.error("[SVP inventoryCleanup] Ошибка удаления:", message);
      showToast(`Ошибка очистки: ${message}`);
    }
  }
  function isDiscoverButton(target) {
    if (!(target instanceof Element)) return false;
    const button = target.closest(ACTION_SELECTORS);
    if (!(button instanceof HTMLButtonElement)) return false;
    return !button.disabled;
  }
  function onClickCapture(event) {
    if (!isDiscoverButton(event.target)) return;
    discoverPending = true;
  }
  function onInventoryCacheUpdated() {
    discoverPending = false;
    void runCleanup();
  }
  function installSetItemInterceptor() {
    if (originalSetItem !== null) return;
    const nativeSetItem = localStorage.setItem;
    originalSetItem = nativeSetItem;
    const wrapper = function(key, value) {
      nativeSetItem.call(localStorage, key, value);
      if (key === INVENTORY_CACHE_KEY && discoverPending) {
        void Promise.resolve().then(onInventoryCacheUpdated);
      }
    };
    localStorage.setItem = wrapper;
    if (localStorage.setItem === wrapper) {
      setItemPatchTarget = "instance";
    } else {
      Storage.prototype.setItem = wrapper;
      setItemPatchTarget = "prototype";
    }
  }
  function uninstallSetItemInterceptor() {
    if (originalSetItem && setItemPatchTarget) {
      if (setItemPatchTarget === "instance") {
        localStorage.setItem = originalSetItem;
      } else {
        Storage.prototype.setItem = originalSetItem;
      }
      originalSetItem = null;
      setItemPatchTarget = null;
    }
  }
  const inventoryCleanup = {
    id: MODULE_ID$2,
    name: {
      en: "Inventory auto-cleanup",
      ru: "Автоочистка инвентаря"
    },
    description: {
      en: "Automatically removes excess items when discovering points. Slow cleanup runs manually from the references OPS tab",
      ru: "Автоматически удаляет лишние предметы при изучении точек. Медленная очистка запускается вручную через кнопку во вкладке ключей в ОРПЦ"
    },
    defaultEnabled: true,
    category: "feature",
    init() {
    },
    enable() {
      document.addEventListener("click", onClickCapture, true);
      installSetItemInterceptor();
      initCleanupSettingsUi();
      installSlowRefsDelete();
    },
    disable() {
      document.removeEventListener("click", onClickCapture, true);
      uninstallSetItemInterceptor();
      discoverPending = false;
      destroyCleanupSettingsUi();
      uninstallSlowRefsDelete();
    }
  };
  function isDevMode() {
    if (/[#&]svp-dev=1/.test(location.hash)) return true;
    return localStorage.getItem("svp_debug") === "1";
  }
  function installDebugHooks() {
    if (!isDevMode()) return;
    window.svpFavs = {
      list: () => Array.from(getFavoritedGuids()),
      count: () => getFavoritesCount(),
      isFav: (guid) => isFavorited(guid),
      add: async (guid) => {
        await addFavorite(guid);
        console.log(`[SVP favoritedPoints] добавлено: ${guid}`);
      },
      remove: async (guid) => {
        await removeFavorite(guid);
        console.log(`[SVP favoritedPoints] удалено: ${guid}`);
      },
      export: exportToJson,
      import: async (json) => {
        const count = await importFromJson(json);
        console.log(`[SVP favoritedPoints] импортировано записей: ${count}`);
        return count;
      },
      clear: async () => {
        const guids = Array.from(getFavoritedGuids());
        for (const guid of guids) {
          await removeFavorite(guid);
        }
        console.log(`[SVP favoritedPoints] очищено записей: ${guids.length}`);
        return guids.length;
      }
    };
  }
  function uninstallDebugHooks() {
    delete window.svpFavs;
  }
  const STAR_CLASS = "svp-fav-star";
  const POPUP_SELECTOR = ".info.popup";
  const IMAGE_BOX_SELECTOR = ".i-image-box";
  const STAR_SVG$1 = `
<svg viewBox="0 0 576 512" width="20" height="20" aria-hidden="true">
  <path d="M287.9 0c9.2 0 17.6 5.2 21.6 13.5l68.6 141.3 153.2 22.6c9 1.3 16.5 7.6 19.3 16.3s.5 18.1-6 24.5L433.6 328.4l26.2 155.6c1.5 9-2.2 18.1-9.7 23.5s-17.3 6-25.3 1.7l-137-73.2L151 509.1c-8.1 4.3-17.9 3.7-25.3-1.7s-11.2-14.5-9.7-23.5l26.2-155.6L31.1 218.2c-6.5-6.4-8.7-15.9-6-24.5s10.3-15 19.3-16.3l153.2-22.6L266.3 13.5C270.4 5.2 278.7 0 287.9 0z"/>
</svg>
`;
  let popupObserver$1 = null;
  let clickAbortController = null;
  let changeHandler$1 = null;
  let installGeneration$1 = 0;
  function findStarButton(popup2) {
    return popup2.querySelector(`.${STAR_CLASS}`);
  }
  function getCurrentGuid(popup2) {
    if (popup2.classList.contains("hidden")) return null;
    if (!(popup2 instanceof HTMLElement)) return null;
    const guid = popup2.dataset.guid;
    return guid && guid.length > 0 ? guid : null;
  }
  function updateButtonState(button, guid) {
    if (guid === null) {
      button.classList.remove("is-filled");
      button.title = "";
      button.disabled = true;
      return;
    }
    button.disabled = false;
    const favorited = isFavorited(guid);
    button.classList.toggle("is-filled", favorited);
    button.title = favorited ? t({ en: "Remove from favorites", ru: "Убрать из избранного" }) : t({ en: "Add to favorites", ru: "Добавить в избранное" });
    button.setAttribute("aria-pressed", favorited ? "true" : "false");
  }
  function injectStarButton(popup2) {
    const imageBox = popup2.querySelector(IMAGE_BOX_SELECTOR);
    if (!imageBox) return;
    if (findStarButton(popup2)) {
      const button2 = findStarButton(popup2);
      if (button2) updateButtonState(button2, getCurrentGuid(popup2));
      return;
    }
    const button = document.createElement("button");
    button.className = STAR_CLASS;
    button.type = "button";
    button.innerHTML = STAR_SVG$1;
    button.setAttribute("aria-pressed", "false");
    clickAbortController = new AbortController();
    button.addEventListener(
      "click",
      (event) => {
        event.stopPropagation();
        event.preventDefault();
        void onStarClick(popup2, button);
      },
      { signal: clickAbortController.signal }
    );
    const refSpan = imageBox.querySelector("#i-ref");
    if (refSpan) {
      refSpan.after(button);
    } else {
      imageBox.appendChild(button);
    }
    updateButtonState(button, getCurrentGuid(popup2));
  }
  async function onStarClick(popup2, button) {
    const guid = getCurrentGuid(popup2);
    if (guid === null) return;
    button.disabled = true;
    try {
      if (isFavorited(guid)) {
        await removeFavorite(guid);
      } else {
        await addFavorite(guid);
      }
      updateButtonState(button, getCurrentGuid(popup2));
    } catch (error) {
      console.error("[SVP favoritedPoints] ошибка сохранения избранного:", error);
      updateButtonState(button, getCurrentGuid(popup2));
    }
  }
  function startObserving$1(popup2) {
    injectStarButton(popup2);
    popupObserver$1 = new MutationObserver(() => {
      injectStarButton(popup2);
    });
    popupObserver$1.observe(popup2, {
      attributes: true,
      attributeFilter: ["class", "data-guid"]
    });
    changeHandler$1 = () => {
      injectStarButton(popup2);
    };
    document.addEventListener(FAVORITES_CHANGED_EVENT, changeHandler$1);
  }
  function installStarButton() {
    if (popupObserver$1) return;
    installGeneration$1++;
    const generation = installGeneration$1;
    const existing = document.querySelector(POPUP_SELECTOR);
    if (existing) {
      startObserving$1(existing);
      return;
    }
    waitForElement(POPUP_SELECTOR).then((popup2) => {
      if (generation !== installGeneration$1) return;
      startObserving$1(popup2);
    }).catch((error) => {
      console.warn("[SVP favoritedPoints] попап точки не найден:", error);
    });
  }
  function uninstallStarButton() {
    var _a;
    installGeneration$1++;
    popupObserver$1 == null ? void 0 : popupObserver$1.disconnect();
    popupObserver$1 = null;
    clickAbortController == null ? void 0 : clickAbortController.abort();
    clickAbortController = null;
    if (changeHandler$1) {
      document.removeEventListener(FAVORITES_CHANGED_EVENT, changeHandler$1);
      changeHandler$1 = null;
    }
    (_a = document.querySelector(`.${STAR_CLASS}`)) == null ? void 0 : _a.remove();
  }
  const FILTER_BAR_CLASS = "svp-fav-filter-bar";
  const FILTER_CHECKBOX_CLASS = "svp-fav-filter-checkbox";
  const FAV_ITEM_CLASS = "svp-is-fav";
  const ITEM_STAR_CLASS = "svp-inv-item-star";
  const GAME_HIDDEN_CLASS = "hidden";
  const FILTER_MARK_CLASS = "svp-fav-filtered";
  const INVENTORY_CONTENT_SELECTOR = ".inventory__content";
  const INVENTORY_POPUP_SELECTOR = ".inventory.popup";
  const REFS_TAB = "3";
  const MAX_CONCURRENT_POINT_FETCHES = 4;
  let activePointFetches = 0;
  const pointFetchQueue = [];
  function scheduleLimitedPointFetch(task) {
    return new Promise((resolve, reject) => {
      const run = () => {
        activePointFetches++;
        task().then(resolve, reject).finally(() => {
          activePointFetches--;
          const next = pointFetchQueue.shift();
          if (next) next();
        });
      };
      if (activePointFetches < MAX_CONCURRENT_POINT_FETCHES) {
        run();
      } else {
        pointFetchQueue.push(run);
      }
    });
  }
  const PLACEHOLDER_CLASS = "svp-fav-placeholder";
  const PLACEHOLDER_LOADED_CLASS = "loaded";
  const PLACEHOLDER_HEADER_CLASS = "svp-fav-placeholder-header";
  const STAR_SVG = `
<svg viewBox="0 0 576 512" width="16" height="16" aria-hidden="true">
  <path d="M287.9 0c9.2 0 17.6 5.2 21.6 13.5l68.6 141.3 153.2 22.6c9 1.3 16.5 7.6 19.3 16.3s.5 18.1-6 24.5L433.6 328.4l26.2 155.6c1.5 9-2.2 18.1-9.7 23.5s-17.3 6-25.3 1.7l-137-73.2L151 509.1c-8.1 4.3-17.9 3.7-25.3-1.7s-11.2-14.5-9.7-23.5l26.2-155.6L31.1 218.2c-6.5-6.4-8.7-15.9-6-24.5s10.3-15 19.3-16.3l153.2-22.6L266.3 13.5C270.4 5.2 278.7 0 287.9 0z"/>
</svg>
`;
  let contentObserver = null;
  let popupObserver = null;
  let filterBar = null;
  let checkbox = null;
  let countSpan = null;
  let changeHandler = null;
  let filterEnabled = false;
  let installGeneration = 0;
  function getCurrentTab(content) {
    if (!(content instanceof HTMLElement)) return null;
    return content.dataset.tab ?? null;
  }
  function updateFilterBarVisibility(content) {
    if (!filterBar) return;
    const isRefsTab = getCurrentTab(content) === REFS_TAB;
    filterBar.classList.toggle("svp-hidden", !isRefsTab);
  }
  function updateCountLabel() {
    if (countSpan) {
      countSpan.textContent = String(getFavoritesCount());
    }
  }
  async function onItemStarClick(event, item, starButton) {
    event.stopPropagation();
    event.preventDefault();
    const pointGuid = item.dataset.ref;
    if (!pointGuid) return;
    starButton.disabled = true;
    try {
      if (isFavorited(pointGuid)) {
        await removeFavorite(pointGuid);
      } else {
        await addFavorite(pointGuid);
      }
    } catch (error) {
      console.error("[SVP favoritedPoints] ошибка сохранения избранного:", error);
    } finally {
      starButton.disabled = false;
    }
  }
  function injectItemStar(item) {
    if (item.querySelector(`.${ITEM_STAR_CLASS}`)) return;
    const leftBlock = item.querySelector(".inventory__item-left");
    if (!leftBlock) return;
    const star = document.createElement("button");
    star.type = "button";
    star.className = ITEM_STAR_CLASS;
    star.innerHTML = STAR_SVG;
    star.addEventListener("click", (event) => {
      void onItemStarClick(event, item, star);
    });
    leftBlock.insertBefore(star, leftBlock.firstChild);
  }
  function updateItemStarState(item) {
    const star = item.querySelector(`.${ITEM_STAR_CLASS}`);
    if (!star) return;
    const pointGuid = item.dataset.ref;
    const favorited = pointGuid !== void 0 && isFavorited(pointGuid);
    star.classList.toggle("is-filled", favorited);
    star.setAttribute("aria-pressed", favorited ? "true" : "false");
    star.title = favorited ? t({ en: "Remove from favorites", ru: "Убрать из избранного" }) : t({ en: "Add to favorites", ru: "Добавить в избранное" });
  }
  function updateItemMarks(content) {
    const items = content.querySelectorAll(".inventory__item[data-ref]");
    for (const item of items) {
      const pointGuid = item.dataset.ref;
      const favorited = pointGuid !== void 0 && pointGuid !== "" && isFavorited(pointGuid);
      item.classList.toggle(FAV_ITEM_CLASS, favorited);
      injectItemStar(item);
      updateItemStarState(item);
    }
  }
  function applyFilter(content) {
    const items = content.querySelectorAll(".inventory__item[data-ref]");
    for (const item of items) {
      const pointGuid = item.dataset.ref;
      const favorited = pointGuid !== void 0 && pointGuid !== "" && isFavorited(pointGuid);
      if (filterEnabled && !favorited) {
        item.classList.add(GAME_HIDDEN_CLASS);
        item.classList.add(FILTER_MARK_CLASS);
      } else if (item.classList.contains(FILTER_MARK_CLASS)) {
        item.classList.remove(GAME_HIDDEN_CLASS);
        item.classList.remove(FILTER_MARK_CLASS);
      }
    }
  }
  function getKeyedGuids(content) {
    const guids = /* @__PURE__ */ new Set();
    const items = content.querySelectorAll(".inventory__item[data-ref]");
    for (const item of items) {
      if (item.classList.contains(PLACEHOLDER_CLASS)) continue;
      const guid = item.dataset.ref;
      if (guid) guids.add(guid);
    }
    return guids;
  }
  function createPlaceholderHeader() {
    const header = document.createElement("div");
    header.className = PLACEHOLDER_HEADER_CLASS;
    header.textContent = t({
      en: "Favorited points without keys",
      ru: "Избранные точки без ключей"
    });
    return header;
  }
  function syncPlaceholderHeader(content) {
    var _a, _b;
    const firstPlaceholder = content.querySelector(`.${PLACEHOLDER_CLASS}`);
    const existingHeader = content.querySelector(`.${PLACEHOLDER_HEADER_CLASS}`);
    if (!firstPlaceholder) {
      existingHeader == null ? void 0 : existingHeader.remove();
      return;
    }
    if (existingHeader) {
      if (existingHeader.nextSibling !== firstPlaceholder) {
        (_a = firstPlaceholder.parentElement) == null ? void 0 : _a.insertBefore(existingHeader, firstPlaceholder);
      }
      return;
    }
    const header = createPlaceholderHeader();
    (_b = firstPlaceholder.parentElement) == null ? void 0 : _b.insertBefore(header, firstPlaceholder);
  }
  function createPlaceholderItem(pointGuid) {
    const item = document.createElement("div");
    item.className = `inventory__item ${PLACEHOLDER_CLASS} ${PLACEHOLDER_LOADED_CLASS}`;
    item.dataset.ref = pointGuid;
    const left = document.createElement("div");
    left.className = "inventory__item-left";
    const title = document.createElement("span");
    title.className = "inventory__item-title";
    title.textContent = t({ en: "Loading…", ru: "Загрузка…" });
    const description = document.createElement("span");
    description.className = "inventory__item-descr";
    description.style.fontStyle = "italic";
    description.textContent = t({ en: "No keys", ru: "Нет ключей" });
    left.appendChild(title);
    left.appendChild(description);
    item.appendChild(left);
    return item;
  }
  async function fetchPointData(pointGuid) {
    try {
      const url = `/api/point?guid=${encodeURIComponent(pointGuid)}&status=1`;
      const response = await fetch(url);
      if (!response.ok) return null;
      const json = await response.json();
      if (json.error) return null;
      const pointData = json.data;
      if (!pointData) return null;
      return {
        title: typeof pointData.t === "string" ? pointData.t : "",
        level: Number(pointData.l ?? 0),
        team: Number(pointData.te ?? 0),
        owner: typeof pointData.o === "string" ? pointData.o : "",
        energy: Number(pointData.e ?? 0),
        coresCount: Number(pointData.co ?? 0)
      };
    } catch {
      return null;
    }
  }
  function populatePlaceholder(item, data) {
    const title = item.querySelector(".inventory__item-title");
    if (title) {
      title.textContent = data.title;
      title.style.color = `var(--team-${data.team})`;
    }
    const description = item.querySelector(".inventory__item-descr");
    if (description) {
      const levelSpan = document.createElement("span");
      levelSpan.style.color = `var(--level-${data.level})`;
      levelSpan.textContent = `Level ${data.level}`;
      const ownerSpan = document.createElement("span");
      ownerSpan.style.color = `var(--team-${data.team})`;
      ownerSpan.className = "profile-link";
      ownerSpan.dataset.name = data.owner;
      ownerSpan.textContent = data.owner || "—";
      description.textContent = "";
      description.style.fontStyle = "";
      description.appendChild(levelSpan);
      description.appendChild(document.createTextNode("; "));
      description.appendChild(ownerSpan);
    }
  }
  function populatePlaceholderError(item) {
    const title = item.querySelector(".inventory__item-title");
    if (title) {
      title.textContent = t({ en: "Failed to load", ru: "Не удалось загрузить" });
    }
  }
  function injectPlaceholders(content) {
    const keyedGuids = getKeyedGuids(content);
    const allFavoriteGuids = getFavoritedGuids();
    for (const guid of allFavoriteGuids) {
      if (keyedGuids.has(guid)) continue;
      const existingPlaceholders = content.querySelectorAll(`.${PLACEHOLDER_CLASS}`);
      let alreadyExists = false;
      for (const existing of existingPlaceholders) {
        if (existing.dataset.ref === guid) {
          alreadyExists = true;
          break;
        }
      }
      if (alreadyExists) continue;
      const placeholder = createPlaceholderItem(guid);
      content.appendChild(placeholder);
      void scheduleLimitedPointFetch(() => fetchPointData(guid)).then((data) => {
        if (!placeholder.isConnected) return;
        if (data) {
          populatePlaceholder(placeholder, data);
        } else {
          populatePlaceholderError(placeholder);
        }
      });
    }
    syncPlaceholderHeader(content);
  }
  function removePlaceholders(content) {
    var _a;
    const placeholders = content.querySelectorAll(`.${PLACEHOLDER_CLASS}`);
    for (const placeholder of placeholders) {
      placeholder.remove();
    }
    (_a = content.querySelector(`.${PLACEHOLDER_HEADER_CLASS}`)) == null ? void 0 : _a.remove();
  }
  function processItems(content) {
    updateItemMarks(content);
    applyFilter(content);
  }
  function setFilterEnabled(content, enabled2) {
    filterEnabled = enabled2;
    if (checkbox) checkbox.checked = enabled2;
    if (enabled2) {
      injectPlaceholders(content);
    } else {
      removePlaceholders(content);
    }
    processItems(content);
    if (content instanceof HTMLElement) {
      content.scrollTop = 0;
    }
    content.dispatchEvent(new Event("scroll", { bubbles: true }));
  }
  function createFilterBar(content) {
    const bar = document.createElement("div");
    bar.className = FILTER_BAR_CLASS;
    const label = document.createElement("label");
    label.className = "svp-fav-filter-label";
    checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.className = FILTER_CHECKBOX_CLASS;
    checkbox.checked = false;
    checkbox.addEventListener("change", () => {
      setFilterEnabled(content, (checkbox == null ? void 0 : checkbox.checked) ?? false);
    });
    const text = document.createElement("span");
    text.textContent = t({ en: "Only ", ru: "Только " });
    const starIcon = document.createElement("span");
    starIcon.className = "svp-fav-filter-star-icon";
    starIcon.innerHTML = '<svg viewBox="0 0 576 512" width="12" height="12" aria-hidden="true"><path d="M287.9 0c9.2 0 17.6 5.2 21.6 13.5l68.6 141.3 153.2 22.6c9 1.3 16.5 7.6 19.3 16.3s.5 18.1-6 24.5L433.6 328.4l26.2 155.6c1.5 9-2.2 18.1-9.7 23.5s-17.3 6-25.3 1.7l-137-73.2L151 509.1c-8.1 4.3-17.9 3.7-25.3-1.7s-11.2-14.5-9.7-23.5l26.2-155.6L31.1 218.2c-6.5-6.4-8.7-15.9-6-24.5s10.3-15 19.3-16.3l153.2-22.6L266.3 13.5C270.4 5.2 278.7 0 287.9 0z"/></svg>';
    countSpan = document.createElement("span");
    countSpan.className = "svp-fav-filter-count";
    updateCountLabel();
    const countWrapper = document.createElement("span");
    countWrapper.appendChild(document.createTextNode("("));
    countWrapper.appendChild(countSpan);
    countWrapper.appendChild(document.createTextNode(")"));
    label.appendChild(checkbox);
    label.appendChild(text);
    label.appendChild(starIcon);
    label.appendChild(countWrapper);
    bar.appendChild(label);
    return bar;
  }
  function ensureFilterBarInjected(content) {
    var _a;
    if (filterBar && filterBar.isConnected) return;
    filterBar = createFilterBar(content);
    (_a = content.parentElement) == null ? void 0 : _a.insertBefore(filterBar, content);
    updateFilterBarVisibility(content);
  }
  function onContentMutation(content) {
    updateFilterBarVisibility(content);
    if (getCurrentTab(content) === REFS_TAB) {
      if (filterEnabled) {
        injectPlaceholders(content);
      }
      processItems(content);
      updateCountLabel();
    }
  }
  function onFavoritesChanged(content) {
    if (getCurrentTab(content) === REFS_TAB) {
      updateItemMarks(content);
    }
    updateCountLabel();
  }
  function onInventoryPopupMutation(popup2, content) {
    if (!popup2.classList.contains("hidden")) {
      if (filterEnabled) {
        setFilterEnabled(content, false);
      }
    }
  }
  function startObserving(content) {
    ensureFilterBarInjected(content);
    onContentMutation(content);
    contentObserver = new MutationObserver(() => {
      onContentMutation(content);
    });
    contentObserver.observe(content, {
      attributes: true,
      attributeFilter: ["data-tab"],
      childList: true
    });
    const popup2 = document.querySelector(INVENTORY_POPUP_SELECTOR);
    if (popup2) {
      popupObserver = new MutationObserver(() => {
        onInventoryPopupMutation(popup2, content);
      });
      popupObserver.observe(popup2, {
        attributes: true,
        attributeFilter: ["class"]
      });
    }
    changeHandler = () => {
      onFavoritesChanged(content);
    };
    document.addEventListener(FAVORITES_CHANGED_EVENT, changeHandler);
  }
  function installInventoryFilter() {
    if (contentObserver) return;
    installGeneration++;
    const generation = installGeneration;
    const existing = document.querySelector(INVENTORY_CONTENT_SELECTOR);
    if (existing) {
      startObserving(existing);
      return;
    }
    waitForElement(INVENTORY_CONTENT_SELECTOR).then((content) => {
      if (generation !== installGeneration) return;
      startObserving(content);
    }).catch((error) => {
      console.warn("[SVP favoritedPoints] контейнер инвентаря не найден:", error);
    });
  }
  function uninstallInventoryFilter() {
    var _a;
    installGeneration++;
    contentObserver == null ? void 0 : contentObserver.disconnect();
    contentObserver = null;
    popupObserver == null ? void 0 : popupObserver.disconnect();
    popupObserver = null;
    if (changeHandler) {
      document.removeEventListener(FAVORITES_CHANGED_EVENT, changeHandler);
      changeHandler = null;
    }
    filterBar == null ? void 0 : filterBar.remove();
    filterBar = null;
    checkbox = null;
    countSpan = null;
    filterEnabled = false;
    const content = document.querySelector(INVENTORY_CONTENT_SELECTOR);
    if (content) {
      removePlaceholders(content);
      const items = content.querySelectorAll(".inventory__item[data-ref]");
      for (const item of items) {
        item.classList.remove(FAV_ITEM_CLASS);
        if (item.classList.contains(FILTER_MARK_CLASS)) {
          item.classList.remove(GAME_HIDDEN_CLASS);
          item.classList.remove(FILTER_MARK_CLASS);
        }
        (_a = item.querySelector(`.${ITEM_STAR_CLASS}`)) == null ? void 0 : _a.remove();
      }
    }
  }
  const STORAGE_KEY = "svp_favoritedPoints";
  function defaultFavoritedPointsSettings() {
    return {
      version: 1,
      hideLastFavRef: true
    };
  }
  function isSettings(value) {
    if (typeof value !== "object" || value === null) return false;
    const record = value;
    return typeof record.version === "number" && typeof record.hideLastFavRef === "boolean";
  }
  function loadFavoritedPointsSettings() {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaultFavoritedPointsSettings();
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return defaultFavoritedPointsSettings();
    }
    if (!isSettings(parsed)) return defaultFavoritedPointsSettings();
    return parsed;
  }
  function saveFavoritedPointsSettings(settings) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  }
  const DRAW_URL_PATTERN = /\/api\/draw(?:\?|$)/;
  let originalFetch = null;
  function matchesDrawList(url, method) {
    if (!DRAW_URL_PATTERN.test(url)) return false;
    const m = (method ?? "GET").toUpperCase();
    return m === "GET";
  }
  function getUrl(input) {
    if (typeof input === "string") return input;
    if (input instanceof URL) return input.href;
    return input.url;
  }
  function getMethod(input, init) {
    if (init == null ? void 0 : init.method) return init.method;
    if (typeof input !== "string" && !(input instanceof URL)) return input.method;
    return void 0;
  }
  function isDrawResponseShape(value) {
    if (typeof value !== "object" || value === null) return false;
    if (!("data" in value)) return false;
    const record = value;
    return Array.isArray(record.data);
  }
  function showHideLastFavRefToast(hidden) {
    const message = t(
      hidden === 1 ? {
        en: `Hidden last key from a favorited point`,
        ru: `Скрыт последний ключ от избранной точки`
      } : {
        en: `Hidden last ${hidden} keys from favorited points`,
        ru: `Скрыты последние ${hidden} ${hidden < 5 ? "ключа" : "ключей"} от избранных точек`
      }
    );
    showToast(message, 4e3);
  }
  async function filterDrawResponse(response) {
    const settings = loadFavoritedPointsSettings();
    if (!settings.hideLastFavRef) return response;
    const favorites = getFavoritedGuids();
    if (favorites.size === 0) return response;
    let parsed;
    try {
      parsed = await response.clone().json();
    } catch {
      return response;
    }
    if (!isDrawResponseShape(parsed)) return response;
    const originalLength = parsed.data.length;
    parsed.data = parsed.data.filter((entry) => {
      const pointGuid = entry.p;
      const amount = entry.a;
      if (typeof pointGuid !== "string" || typeof amount !== "number") return true;
      const isLastFav = favorites.has(pointGuid) && amount === 1;
      return !isLastFav;
    });
    const hidden = originalLength - parsed.data.length;
    if (hidden > 0) {
      showHideLastFavRefToast(hidden);
    }
    const modified = new Response(JSON.stringify(parsed), {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers
    });
    Object.defineProperty(modified, "url", { value: response.url });
    return modified;
  }
  function installLastRefProtection() {
    if (originalFetch) return;
    originalFetch = window.fetch;
    const native = originalFetch;
    window.fetch = function(input, init) {
      const url = getUrl(input);
      const method = getMethod(input, init);
      const promise = native.call(this, input, init);
      if (!matchesDrawList(url, method)) return promise;
      return promise.then((response) => filterDrawResponse(response));
    };
  }
  function uninstallLastRefProtection() {
    if (!originalFetch) return;
    window.fetch = originalFetch;
    originalFetch = null;
  }
  const MODULE_ID$1 = "favoritedPoints";
  const CONFIGURE_BUTTON_CLASS = "svp-fav-configure-button";
  const PANEL_CLASS = "svp-fav-settings-panel";
  let panel = null;
  let configureButton = null;
  let moduleRowObserver = null;
  function buildPanel() {
    const settings = loadFavoritedPointsSettings();
    const element = document.createElement("div");
    element.className = PANEL_CLASS;
    const header = document.createElement("div");
    header.className = "svp-fav-settings-header";
    header.textContent = t({ en: "Favorited points settings", ru: "Настройки избранных точек" });
    element.appendChild(header);
    const content = document.createElement("div");
    content.className = "svp-fav-settings-content";
    const hideLastRow = document.createElement("label");
    hideLastRow.className = "svp-fav-settings-checkbox-row";
    const hideLastCheckbox = document.createElement("input");
    hideLastCheckbox.type = "checkbox";
    hideLastCheckbox.checked = settings.hideLastFavRef;
    hideLastCheckbox.addEventListener("change", () => {
      const updated = loadFavoritedPointsSettings();
      updated.hideLastFavRef = hideLastCheckbox.checked;
      saveFavoritedPointsSettings(updated);
    });
    hideLastRow.appendChild(hideLastCheckbox);
    const hideLastLabel = document.createElement("span");
    hideLastLabel.textContent = t({
      en: " Protect last key of favorited point when drawing",
      ru: " Защищать последний ключ от избранной точки при рисовании"
    });
    hideLastRow.appendChild(hideLastLabel);
    content.appendChild(hideLastRow);
    const counter = document.createElement("div");
    counter.className = "svp-fav-settings-counter";
    counter.textContent = t({ en: "Favorited points total: ", ru: "Всего избранных точек: " }) + String(getFavoritesCount());
    content.appendChild(counter);
    const importWrapper = document.createElement("div");
    importWrapper.className = "svp-fav-settings-import-wrapper";
    const importLabel = document.createElement("label");
    importLabel.className = "svp-fav-settings-button";
    importLabel.textContent = t({ en: "⬆️ Import from JSON", ru: "⬆️ Импорт из JSON" });
    const importInput = document.createElement("input");
    importInput.type = "file";
    importInput.accept = "application/json,.json";
    importInput.style.display = "none";
    importInput.addEventListener("change", () => {
      var _a;
      const file = (_a = importInput.files) == null ? void 0 : _a[0];
      if (file) {
        void doImport(file, counter);
      }
      importInput.value = "";
    });
    importLabel.appendChild(importInput);
    importWrapper.appendChild(importLabel);
    content.appendChild(importWrapper);
    const importWarning = document.createElement("div");
    importWarning.className = "svp-fav-settings-warning";
    importWarning.textContent = t({
      en: "⚠️ Current favorites list will be completely replaced",
      ru: "⚠️ Текущий список избранного будет полностью перезаписан"
    });
    content.appendChild(importWarning);
    const exportButton = document.createElement("button");
    exportButton.type = "button";
    exportButton.className = "svp-fav-settings-button";
    exportButton.textContent = t({ en: "⬇️ Download JSON", ru: "⬇️ Скачать JSON" });
    exportButton.addEventListener("click", () => {
      void downloadExport();
    });
    content.appendChild(exportButton);
    element.appendChild(content);
    const footer = document.createElement("div");
    footer.className = "svp-fav-settings-footer";
    const closeButton2 = document.createElement("button");
    closeButton2.type = "button";
    closeButton2.className = "svp-fav-settings-button";
    closeButton2.textContent = t({ en: "Close", ru: "Закрыть" });
    closeButton2.addEventListener("click", () => {
      element.remove();
      panel = null;
    });
    footer.appendChild(closeButton2);
    element.appendChild(footer);
    return element;
  }
  async function downloadExport() {
    try {
      const json = await exportToJson();
      const blob = new Blob([json], { type: "application/json" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      const date = (/* @__PURE__ */ new Date()).toISOString().slice(0, 10);
      link.download = `svp-favorites-${date}.json`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Неизвестная ошибка";
      alert(t({ en: "Export error: ", ru: "Ошибка экспорта: " }) + message);
    }
  }
  async function doImport(file, counterElement) {
    try {
      const text = await file.text();
      const added = await importFromJson(text);
      counterElement.textContent = t({ en: "Favorited points total: ", ru: "Всего избранных точек: " }) + String(getFavoritesCount());
      alert(t({ en: "Records imported: ", ru: "Импортировано записей: " }) + String(added));
    } catch (error) {
      const message = error instanceof Error ? error.message : "Неизвестная ошибка";
      alert(t({ en: "Import error: ", ru: "Ошибка импорта: " }) + message);
    }
  }
  function openPanel() {
    if (panel) panel.remove();
    panel = buildPanel();
    document.body.appendChild(panel);
  }
  function injectConfigureButton() {
    const allIds = document.querySelectorAll(".svp-module-id");
    for (const idElement of allIds) {
      if (idElement.textContent !== MODULE_ID$1) continue;
      const row = idElement.closest(".svp-module-row");
      if (!row) continue;
      if (row.querySelector(`.${CONFIGURE_BUTTON_CLASS}`)) return;
      const nameLine = row.querySelector(".svp-module-name-line");
      if (!nameLine) continue;
      configureButton = document.createElement("button");
      configureButton.className = CONFIGURE_BUTTON_CLASS;
      configureButton.textContent = t({ en: "Configure", ru: "Настроить" });
      configureButton.addEventListener("click", (event) => {
        event.stopPropagation();
        openPanel();
      });
      nameLine.appendChild(configureButton);
      return;
    }
  }
  let rafId = null;
  function installSettingsUi() {
    injectConfigureButton();
    moduleRowObserver = new MutationObserver(() => {
      if (rafId !== null) return;
      rafId = requestAnimationFrame(() => {
        rafId = null;
        if (!document.querySelector(`.${CONFIGURE_BUTTON_CLASS}`)) {
          injectConfigureButton();
        }
      });
    });
    moduleRowObserver.observe(document.body, { childList: true, subtree: true });
  }
  function uninstallSettingsUi() {
    if (rafId !== null) {
      cancelAnimationFrame(rafId);
      rafId = null;
    }
    moduleRowObserver == null ? void 0 : moduleRowObserver.disconnect();
    moduleRowObserver = null;
    panel == null ? void 0 : panel.remove();
    panel = null;
    configureButton == null ? void 0 : configureButton.remove();
    configureButton = null;
  }
  const styles = ".svp-fav-star{position:absolute;bottom:0;right:0;width:28px;height:28px;padding:0;margin:0;border:none;background:#00000059;border-radius:50%;color:#ccc;cursor:pointer;display:flex;align-items:center;justify-content:center;z-index:2;transition:color .15s ease,transform .15s ease}.i-image-box #i-ref{margin-right:32px}.svp-fav-star svg{fill:currentColor;stroke:#0009;stroke-width:24;paint-order:stroke fill}.svp-fav-star:hover{transform:scale(1.1)}.svp-fav-star.is-filled{color:#fc3}.svp-fav-star[disabled]{opacity:.4;cursor:default}.i-image-box,.inventory__content{position:relative}.svp-fav-filter-bar{display:flex;align-items:center;padding:4px;border-bottom:1px solid var(--border, rgba(255, 255, 255, .1));font-size:.9em}.svp-fav-filter-bar.svp-hidden{display:none}.svp-fav-filter-label{display:inline-flex;align-items:center;gap:4px;cursor:pointer;-webkit-user-select:none;user-select:none}.svp-fav-filter-star-icon{display:inline-flex;color:#fc3}.svp-fav-filter-star-icon svg{fill:currentColor}.inventory__item[data-ref] .inventory__item-left{position:relative}.inventory__item[data-ref] .inventory__item-title{padding-left:26px;display:inline-block}.svp-inv-item-star{position:absolute;top:0;left:0;width:22px;height:22px;padding:0;margin:0;border:none;background:#0000004d;border-radius:50%;color:#999;cursor:pointer;display:flex;align-items:center;justify-content:center;z-index:2;transition:color .15s ease}.svp-inv-item-star svg{fill:currentColor;stroke:#0009;stroke-width:24;paint-order:stroke fill}.svp-inv-item-star.is-filled{color:#fc3}.svp-inv-item-star[disabled]{opacity:.4;cursor:default}.svp-fav-placeholder{grid-template-columns:1fr!important}.svp-fav-placeholder .inventory__item-descr{opacity:.6}.svp-fav-placeholder-header{padding:6px 8px;margin-top:8px;font-size:.85em;font-weight:700;text-transform:uppercase;letter-spacing:.04em;opacity:.7;border-top:1px solid var(--border, rgba(255, 255, 255, .15))}.svp-fav-configure-button{margin-left:8px;padding:2px 8px;border:1px solid var(--border, rgba(255, 255, 255, .2));border-radius:3px;background:transparent;color:var(--text, #ddd);font-size:11px;cursor:pointer}.svp-fav-configure-button:hover{background:var(--accent, #ffcc33);color:var(--background, #000)}.svp-fav-settings-panel{position:fixed;top:0;right:0;bottom:0;left:0;z-index:10011;display:flex;flex-direction:column;background:var(--background, #1a1a1a);color:var(--text, #ddd);padding:16px;max-width:600px;margin:0 auto}.svp-fav-settings-header{font-size:14px;font-weight:700;padding-bottom:8px;border-bottom:1px solid var(--border, #444)}.svp-fav-settings-content{flex:1;padding:12px 0;display:flex;flex-direction:column;gap:10px;font-size:13px}.svp-fav-settings-checkbox-row{display:flex;align-items:center;gap:6px;cursor:pointer;-webkit-user-select:none;user-select:none}.svp-fav-settings-warning{font-size:11px;color:var(--accent, #ffcc33);margin-top:-6px;margin-bottom:14px}.svp-fav-settings-counter{color:var(--text-disabled, #888);font-size:12px}.svp-fav-settings-import-wrapper{display:block}.svp-fav-settings-content .svp-fav-settings-button{display:block;width:100%;box-sizing:border-box}.svp-fav-settings-button{display:inline-block;padding:6px 12px;border:1px solid var(--border, #444);border-radius:4px;background:transparent;color:var(--text, #ddd);font-size:12px;cursor:pointer;text-align:center}.svp-fav-settings-button:hover{background:var(--accent, #ffcc33);color:var(--background, #000);border-color:var(--accent, #ffcc33)}.svp-fav-settings-footer{padding-top:8px;border-top:1px solid var(--border, #444);display:flex;justify-content:flex-end;gap:8px}";
  const MODULE_ID = "favoritedPoints";
  const favoritedPoints = {
    id: MODULE_ID,
    name: {
      en: "Favorited points",
      ru: "Избранные точки"
    },
    description: {
      en: "Mark points with a star — their keys will never be deleted by auto-cleanup. List is shared with CUI. Import/export via JSON in settings.",
      ru: "Пометить точки звездой — ключи от них не удалит автоочистка. Список шарится с CUI. Импорт/экспорт через JSON в настройках."
    },
    defaultEnabled: true,
    category: "feature",
    async init() {
      await loadFavorites();
    },
    enable() {
      injectStyles(styles, MODULE_ID);
      installStarButton();
      installInventoryFilter();
      installLastRefProtection();
      installSettingsUi();
      installDebugHooks();
    },
    disable() {
      uninstallStarButton();
      uninstallInventoryFilter();
      uninstallLastRefProtection();
      uninstallSettingsUi();
      removeStyles(MODULE_ID);
      uninstallDebugHooks();
    }
  };
  if (!isDisabled()) {
    installGameScriptPatcher();
    initOlMapCapture();
    installGameVersionCapture();
    async function init() {
      initErrorLog();
      await initGameVersionDetection();
      if (!ensureSbgVersionSupported()) return;
      installSbgFlavor();
      bootstrap([
        // ui
        enhancedMainScreen,
        enhancedPointPopupUi,
        swipeToClosePopup,
        groupErrorToasts,
        removeAttackCloseButton,
        // feature (favoritedPoints ПЕРЕД inventoryCleanup — зависимость init)
        favoritedPoints,
        inventoryCleanup,
        keepScreenOn,
        repairAtFullCharge,
        // map
        shiftMapCenterDown,
        largerPointTapArea,
        ngrsZoom,
        keyCountOnPoints,
        singleFingerRotation,
        mapTileLayers,
        drawTools,
        // feature (map-зависимые)
        nextPointNavigation,
        refsOnMap,
        // fix
        drawButtonFix
      ]);
    }
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", () => void init());
    } else {
      void init();
    }
  }

})();