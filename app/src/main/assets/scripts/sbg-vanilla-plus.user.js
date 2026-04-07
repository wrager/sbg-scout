// ==UserScript==
// @name         SBG Vanilla+
// @namespace    https://github.com/wrager/sbg-vanilla-plus
// @version      0.8.1
// @author       wrager
// @description  UI/UX enhancements for SBG (SBG v0.6.0)
// @license      MIT
// @homepage     https://github.com/wrager/sbg-vanilla-plus
// @homepageURL  https://github.com/wrager/sbg-vanilla-plus
// @source       https://github.com/wrager/sbg-vanilla-plus.git
// @downloadURL  https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js
// @updateURL    https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js
// @match        https://sbg-game.ru/app/*
// @grant        none
// @run-at       document-start
// ==/UserScript==

(function () {
  'use strict';

  const STORAGE_KEY$2 = "svp_disabled";
  function isDisabled() {
    const hash = location.hash;
    const match = /[#&]svp-disabled=([01])/.exec(hash);
    if (match) {
      if (match[1] === "1") {
        sessionStorage.setItem(STORAGE_KEY$2, "1");
      } else {
        sessionStorage.removeItem(STORAGE_KEY$2);
      }
    }
    return sessionStorage.getItem(STORAGE_KEY$2) === "1";
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
        if (mod.status !== "failed" && isEnabled(mod.id)) {
          const result = runModuleAction(mod.enable.bind(mod), enableErrorHandler);
          if (result instanceof Promise) {
            void result.then(markReady).catch(enableErrorHandler);
            return;
          }
        }
        markReady();
      };
      const initResult = runModuleAction(mod.init.bind(mod), initErrorHandler);
      if (initResult instanceof Promise) {
        void initResult.then(enableIfNeeded).catch(initErrorHandler);
      } else {
        enableIfNeeded();
      }
    }
  }
  const SETTINGS_VERSION = 3;
  const DEFAULT_SETTINGS = {
    version: SETTINGS_VERSION,
    modules: {},
    errors: {}
  };
  const STORAGE_KEY$1 = "svp_settings";
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
      const raw = localStorage.getItem(STORAGE_KEY$1);
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
    localStorage.setItem(STORAGE_KEY$1, JSON.stringify(settings));
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
      version: "0.8.1",
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
  const PANEL_ID$1 = "svp-settings-panel";
  const BTN_ID = "svp-settings-btn";
  const PANEL_STYLES = `
.svp-settings-btn {
  width: 36px;
  height: 36px;
  border: none;
  background-color: buttonface;
  border-radius: 4px;
  font-size: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

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

.svp-module-checkbox {
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
  const CATEGORY_ORDER = ["ui", "map", "feature", "utility", "fix"];
  const SETTINGS_TITLE = {
    en: "SBG Vanilla+ Settings",
    ru: "Настройки SBG Vanilla+"
  };
  const RELOAD_LABEL = {
    en: "Page will reload on toggle",
    ru: "При переключении происходит перезагрузка"
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
    const checkbox = createCheckbox(enabled2, onChange);
    row.appendChild(checkbox);
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
    return { row, checkbox, setError };
  }
  function fillSection(section, modules, category, errorDisplay, checkboxMap, onAnyToggle) {
    const title = document.createElement("div");
    title.className = "svp-settings-section-title";
    title.textContent = t(CATEGORY_LABELS[category]);
    section.appendChild(title);
    let settings = loadSettings();
    for (const mod of modules) {
      const enabled2 = isModuleEnabled(settings, mod.id, mod.defaultEnabled);
      const errorMessage = settings.errors[mod.id] ?? null;
      const { row, checkbox, setError } = createModuleRow(
        mod,
        enabled2,
        (newEnabled) => {
          settings = setModuleEnabled(settings, mod.id, newEnabled);
          saveSettings(settings);
          if (mod.requiresReload) {
            location.hash = "svp-settings";
            location.reload();
            return;
          }
          const phaseLabel = newEnabled ? "включении" : "выключении";
          function onToggleError(e) {
            const message = e instanceof Error ? e.message : String(e);
            console.error(`[SVP] Ошибка при ${phaseLabel} модуля "${t(mod.name)}":`, e);
            mod.status = "failed";
            settings = setModuleError(settings, mod.id, message);
            saveSettings(settings);
            setError(message);
          }
          const toggleAction = newEnabled ? mod.enable.bind(mod) : mod.disable.bind(mod);
          void runModuleAction(toggleAction, onToggleError);
          if (mod.status !== "failed") {
            mod.status = "ready";
            settings = clearModuleError(settings, mod.id);
            saveSettings(settings);
            setError(null);
          }
          onAnyToggle();
        },
        errorMessage
      );
      checkboxMap.set(mod.id, checkbox);
      errorDisplay.set(mod.id, setError);
      section.appendChild(row);
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
    toggleAllCheckbox.className = "svp-module-checkbox";
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
    const grouped = /* @__PURE__ */ new Map();
    for (const mod of modules) {
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
    updateMasterState();
    toggleAllCheckbox.addEventListener("change", () => {
      const enableAll = toggleAllCheckbox.checked;
      let settings = loadSettings();
      let needsReload = false;
      for (const mod of modules) {
        let onToggleError = function(e) {
          const message = e instanceof Error ? e.message : String(e);
          console.error(`[SVP] Ошибка при ${phaseLabel} модуля "${t(mod.name)}":`, e);
          mod.status = "failed";
          settings = setModuleError(settings, mod.id, message);
          const setError = errorDisplay.get(mod.id);
          setError == null ? void 0 : setError(message);
        };
        const checkbox = checkboxMap.get(mod.id);
        if (!checkbox || checkbox.checked === enableAll) continue;
        checkbox.checked = enableAll;
        settings = setModuleEnabled(settings, mod.id, enableAll);
        if (mod.requiresReload) {
          needsReload = true;
          continue;
        }
        const phaseLabel = enableAll ? "включении" : "выключении";
        const toggleAction = enableAll ? mod.enable.bind(mod) : mod.disable.bind(mod);
        void runModuleAction(toggleAction, onToggleError);
        if (mod.status !== "failed") {
          mod.status = "ready";
          settings = clearModuleError(settings, mod.id);
          const setError = errorDisplay.get(mod.id);
          setError == null ? void 0 : setError(null);
        }
      }
      saveSettings(settings);
      updateMasterState();
      if (needsReload) {
        location.hash = "svp-settings";
        location.reload();
      }
    });
    panel2.appendChild(content);
    const footer = document.createElement("div");
    footer.className = "svp-settings-footer";
    const version = document.createElement("span");
    version.className = "svp-settings-version";
    version.textContent = `SBG Vanilla+ v${"0.8.1"}`;
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
    const btn = document.createElement("button");
    btn.className = "svp-settings-btn";
    btn.id = BTN_ID;
    btn.textContent = "⚙";
    btn.title = t(SETTINGS_TITLE);
    btn.addEventListener("click", () => {
      panel2.classList.toggle("svp-open");
      requestAnimationFrame(updateScrollIndicators);
    });
    const container = document.querySelector(".bottom-container");
    if (container) {
      container.appendChild(btn);
    } else {
      document.body.appendChild(btn);
    }
    if (location.hash.includes("svp-settings")) {
      panel2.classList.add("svp-open");
      history.replaceState(null, "", location.pathname + location.search);
      requestAnimationFrame(updateScrollIndicators);
    }
  }
  function bootstrap(modules) {
    registerModules(modules);
    let settings = loadSettings();
    settings = persistModuleDefaults(settings, modules);
    const errorDisplay = /* @__PURE__ */ new Map();
    initModules(
      modules,
      (id) => {
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
  const FLAVOR_VALUE = `VanillaPlus/${"0.8.1"}`;
  function installSbgFlavor() {
    const originalFetch = window.fetch;
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
      return originalFetch.call(this, input, { ...init, headers });
    };
  }
  const css$1 = ".topleft-container.svp-collapsed{display:flex;flex-direction:row;align-items:center;gap:4px;padding:4px 37px 4px 6px;margin:0;cursor:pointer;border:1px var(--border) solid!important;background:var(--background)!important;color:var(--text)}.topleft-container.svp-collapsed .self-info{display:flex;align-items:center;margin:0;padding:0}.topleft-container.svp-collapsed .game-menu{display:inline-flex;margin:0;padding:0}#svp-inv-summary{font-size:14px;white-space:nowrap}#svp-top-toggle,#svp-top-expand{position:fixed;z-index:1;font-size:10px;line-height:1;cursor:pointer;padding:4px 8px;color:var(--text);background:var(--background);border:1px var(--border) solid;border-radius:6px;opacity:.8;user-select:none;-webkit-user-select:none;-webkit-tap-highlight-color:transparent}#svp-top-toggle:active,#svp-top-expand:active{opacity:1}#attack-menu{position:fixed;left:50%;transform:translate(-50%);height:27pt}";
  const MODULE_ID$h = "enhancedMainScreen";
  const SUMMARY_ID = "svp-inv-summary";
  const TOGGLE_ID = "svp-top-toggle";
  const EXPAND_ID = "svp-top-expand";
  let cleanup = null;
  function createSummary(container) {
    const invSpan = $("#self-info__inv", container);
    const limSpan = $("#self-info__inv-lim", container);
    const invEntry = invSpan == null ? void 0 : invSpan.closest(".self-info__entry");
    const summary = document.createElement("span");
    summary.id = SUMMARY_ID;
    const update = () => {
      const inv = (invSpan == null ? void 0 : invSpan.textContent) ?? "?";
      const lim = (limSpan == null ? void 0 : limSpan.textContent) ?? "?";
      summary.textContent = `${inv}/${lim}`;
      if (invEntry instanceof HTMLElement) {
        summary.style.color = invEntry.style.color;
      }
    };
    update();
    const observer2 = new MutationObserver(update);
    if (invSpan) observer2.observe(invSpan, { childList: true, characterData: true, subtree: true });
    if (limSpan) observer2.observe(limSpan, { childList: true, characterData: true, subtree: true });
    if (invEntry) observer2.observe(invEntry, { attributes: true, attributeFilter: ["style"] });
    return summary;
  }
  function isHTMLElement(el) {
    return el instanceof HTMLElement;
  }
  async function setup() {
    const container = await waitForElement(".topleft-container");
    if (!isHTMLElement(container)) return () => {
    };
    const selfInfo = $(".self-info", container);
    if (!isHTMLElement(selfInfo)) return () => {
    };
    const opsBtn = $("#ops", container);
    if (!isHTMLElement(opsBtn)) return () => {
    };
    const allEntries = $$(".self-info__entry", container).filter(isHTMLElement);
    const extraButtons = $$(".game-menu button:not(#ops)", container).filter(isHTMLElement);
    const effects = $(".effects", container);
    const hiddenEls = [...allEntries, ...extraButtons, ...isHTMLElement(effects) ? [effects] : []];
    const toggle = document.createElement("div");
    toggle.id = TOGGLE_ID;
    toggle.textContent = "▲";
    document.body.appendChild(toggle);
    const expandBtn = document.createElement("div");
    expandBtn.id = EXPAND_ID;
    expandBtn.textContent = "▼";
    document.body.appendChild(expandBtn);
    const summary = createSummary(container);
    selfInfo.appendChild(summary);
    let collapsed = false;
    const positionToggle = () => {
      const rect = container.getBoundingClientRect();
      toggle.style.top = `${rect.top + 4}px`;
      toggle.style.left = `${rect.right - toggle.offsetWidth - 4}px`;
    };
    const positionExpand = () => {
      const opsRect = opsBtn.getBoundingClientRect();
      expandBtn.style.top = `${opsRect.top + (opsRect.height - expandBtn.offsetHeight) / 2}px`;
      expandBtn.style.left = `${opsRect.right + 4}px`;
    };
    const resizeObserver = new ResizeObserver(() => {
      if (collapsed) positionExpand();
    });
    resizeObserver.observe(container);
    const setCollapsed = (value) => {
      collapsed = value;
      for (const el of hiddenEls) {
        el.style.display = collapsed ? "none" : "";
      }
      summary.style.display = collapsed ? "" : "none";
      toggle.style.display = collapsed ? "none" : "";
      expandBtn.style.display = collapsed ? "" : "none";
      selfInfo.style.border = collapsed ? "none" : "";
      container.classList.toggle("svp-collapsed", collapsed);
      if (!collapsed) {
        requestAnimationFrame(positionToggle);
      }
    };
    setCollapsed(true);
    const onExpand = (e) => {
      if (!collapsed) return;
      const target = e.target;
      if (!(target instanceof Element)) return;
      if (target.closest("#ops")) return;
      e.stopPropagation();
      e.preventDefault();
      setCollapsed(false);
    };
    const onExpandBtn = (e) => {
      e.stopPropagation();
      e.preventDefault();
      setCollapsed(false);
    };
    const onCollapse = (e) => {
      e.stopPropagation();
      e.preventDefault();
      setCollapsed(true);
    };
    container.addEventListener("touchstart", onExpand, { passive: false });
    container.addEventListener("mousedown", onExpand);
    expandBtn.addEventListener("touchstart", onExpandBtn, { passive: false });
    expandBtn.addEventListener("mousedown", onExpandBtn);
    toggle.addEventListener("touchstart", onCollapse, { passive: false });
    toggle.addEventListener("mousedown", onCollapse);
    return () => {
      resizeObserver.disconnect();
      container.removeEventListener("touchstart", onExpand);
      container.removeEventListener("mousedown", onExpand);
      expandBtn.removeEventListener("touchstart", onExpandBtn);
      expandBtn.removeEventListener("mousedown", onExpandBtn);
      toggle.removeEventListener("touchstart", onCollapse);
      toggle.removeEventListener("mousedown", onCollapse);
      setCollapsed(false);
      toggle.remove();
      expandBtn.remove();
      summary.remove();
    };
  }
  const enhancedMainScreen = {
    id: MODULE_ID$h,
    name: { en: "Enhanced Main Screen", ru: "Улучшенный главный экран" },
    description: {
      en: "Collapses the top-left panel and centers the attack button on the map screen",
      ru: "Сворачивает верхнюю панель и центрирует кнопку атаки на экране с картой"
    },
    defaultEnabled: true,
    category: "ui",
    init() {
    },
    enable() {
      injectStyles(css$1, MODULE_ID$h);
      return setup().then((fn) => {
        cleanup = fn;
      });
    },
    disable() {
      removeStyles(MODULE_ID$h);
      cleanup == null ? void 0 : cleanup();
      cleanup = null;
    }
  };
  const styles$5 = ".info.popup .i-buttons button{min-height:72px;display:flex;align-items:center;justify-content:center}.i-stat__entry:not(.i-stat__cores){font-size:.7rem}.cores-list__level{font-size:1rem}#magic-deploy-btn{position:fixed;bottom:5px;left:5px;width:32px;height:32px;min-height:auto}";
  const MODULE_ID$g = "enhancedPointPopupUi";
  const enhancedPointPopupUi = {
    id: MODULE_ID$g,
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
      injectStyles(styles$5, MODULE_ID$g);
    },
    disable() {
      removeStyles(MODULE_ID$g);
    }
  };
  const styles$4 = ".info.popup{touch-action:pan-y}.info.popup .deploy-slider-wrp{touch-action:manipulation}.info.popup.svp-swipe-animating{transition:translate .3s ease-out,rotate .3s ease-out,opacity .3s ease-out}";
  const MODULE_ID$f = "swipeToClosePopup";
  const POPUP_SELECTOR = ".info.popup";
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
    id: MODULE_ID$f,
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
      const element = $(POPUP_SELECTOR);
      if (!(element instanceof HTMLElement)) return;
      popup = element;
      injectStyles(styles$4, MODULE_ID$f);
      addListeners$2();
      startPopupObserver();
    },
    disable() {
      removeListeners$2();
      stopPopupObserver();
      cleanupAnimation();
      removeStyles(MODULE_ID$f);
      lastObservedGuid = null;
      popup = null;
    }
  };
  const MODULE_ID$e = "shiftMapCenterDown";
  const PADDING_FACTOR = 0.35;
  const ACTION_PANEL_SELECTORS = ".attack-slider-wrp, .draw-slider-wrp";
  let map$5 = null;
  let topPadding = 0;
  let inflateForPadding = false;
  let actionObserver = null;
  let actionPanelActive = false;
  let actionRafId = null;
  function applyPadding(padding) {
    if (!map$5) return;
    const view = map$5.getView();
    const center = view.getCenter();
    view.padding = padding;
    view.setCenter(center);
  }
  function handleActionPanelChange() {
    const openPanel = document.querySelector(
      ".attack-slider-wrp:not(.hidden), .draw-slider-wrp:not(.hidden)"
    );
    if (openPanel && !actionPanelActive) {
      actionPanelActive = true;
      const panelHeight = openPanel.getBoundingClientRect().height;
      applyPadding([0, 0, panelHeight, 0]);
    } else if (!openPanel && actionPanelActive) {
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
  const shiftMapCenterDown = {
    id: MODULE_ID$e,
    name: { en: "Shift Map Center Down", ru: "Сдвиг центра карты вниз" },
    description: {
      en: "Moves map center down so you see more ahead while moving",
      ru: "Сдвигает центр карты вниз, чтобы видеть больше карты впереди по ходу движения"
    },
    defaultEnabled: true,
    category: "map",
    init() {
      topPadding = Math.round(window.innerHeight * PADDING_FACTOR);
      return getOlMap().then((olMap2) => {
        map$5 = olMap2;
        const view = olMap2.getView();
        const originalCalculateExtent = view.calculateExtent.bind(view);
        view.calculateExtent = (size) => {
          if (inflateForPadding && size) {
            return originalCalculateExtent([size[0], size[1] + topPadding]);
          }
          return originalCalculateExtent(size);
        };
      });
    },
    enable() {
      inflateForPadding = true;
      applyPadding([topPadding, 0, 0, 0]);
      startActionObserver();
    },
    disable() {
      inflateForPadding = false;
      stopActionObserver();
      actionPanelActive = false;
      applyPadding([0, 0, 0, 0]);
    }
  };
  const MODULE_ID$d = "disableDoubleTapZoom";
  let disabledInteractions$1 = [];
  let enabled$3 = false;
  function isDoubleClickZoom$1(interaction) {
    var _a, _b;
    const DoubleClickZoom = (_b = (_a = window.ol) == null ? void 0 : _a.interaction) == null ? void 0 : _b.DoubleClickZoom;
    return DoubleClickZoom !== void 0 && interaction instanceof DoubleClickZoom;
  }
  const disableDoubleTapZoom = {
    id: MODULE_ID$d,
    name: { en: "Disable Double-Tap Zoom", ru: "Отключить зум по двойному тапу" },
    description: {
      en: "Disables double-tap zoom to prevent accidental zooming",
      ru: "Отключает зум по двойному тапу для предотвращения случайного зума"
    },
    defaultEnabled: true,
    category: "map",
    init() {
    },
    enable() {
      enabled$3 = true;
      return getOlMap().then((map2) => {
        if (!enabled$3) return;
        const interactions = map2.getInteractions().getArray();
        disabledInteractions$1 = interactions.filter(isDoubleClickZoom$1);
        for (const interaction of disabledInteractions$1) {
          interaction.setActive(false);
        }
      });
    },
    disable() {
      enabled$3 = false;
      for (const interaction of disabledInteractions$1) {
        interaction.setActive(true);
      }
      disabledInteractions$1 = [];
    }
  };
  const MODULE_ID$c = "ngrsZoom";
  const TAP_DURATION_THRESHOLD = 200;
  const MAX_TAP_GAP = 300;
  const MAX_TAP_DISTANCE = 30;
  const DRAG_THRESHOLD = 5;
  const ZOOM_SENSITIVITY = 0.01;
  let map$4 = null;
  let enabled$2 = false;
  let disabledInteractions = [];
  let dragPanControl$1 = null;
  let state = "idle";
  let firstTapStartTimestamp = 0;
  let firstTapX = 0;
  let firstTapY = 0;
  let secondTapTimer = null;
  let initialY = 0;
  let initialZoom = 0;
  function isDoubleClickZoom(interaction) {
    var _a, _b;
    const DoubleClickZoom = (_b = (_a = window.ol) == null ? void 0 : _a.interaction) == null ? void 0 : _b.DoubleClickZoom;
    return DoubleClickZoom !== void 0 && interaction instanceof DoubleClickZoom;
  }
  function resetGesture$1() {
    state = "idle";
    dragPanControl$1 == null ? void 0 : dragPanControl$1.restore();
    if (secondTapTimer !== null) {
      clearTimeout(secondTapTimer);
      secondTapTimer = null;
    }
  }
  function distanceBetweenTaps(x, y) {
    return Math.sqrt((x - firstTapX) ** 2 + (y - firstTapY) ** 2);
  }
  function applyZoom(currentY) {
    if (!map$4) return;
    const view = map$4.getView();
    if (!view.setZoom) return;
    const deltaY = initialY - currentY;
    view.setZoom(initialZoom + deltaY * ZOOM_SENSITIVITY);
  }
  function onTouchStart$1(event) {
    var _a;
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
      const view = map$4 == null ? void 0 : map$4.getView();
      const zoom = (_a = view == null ? void 0 : view.getZoom) == null ? void 0 : _a.call(view);
      if (zoom === void 0) {
        resetGesture$1();
        return;
      }
      state = "secondTapDown";
      initialY = touch.clientY;
      initialZoom = zoom;
      dragPanControl$1 == null ? void 0 : dragPanControl$1.disable();
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
    if (!map$4) return;
    const interactions = map$4.getInteractions().getArray();
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
    id: MODULE_ID$c,
    name: {
      en: "Ngrs Zoom",
      ru: "Нгрс-зум"
    },
    description: {
      en: "Double-tap and drag up/down to zoom in/out smoothly",
      ru: "Двойной тап и перетаскивание вверх/вниз для плавного зума"
    },
    defaultEnabled: true,
    category: "map",
    init() {
      return getOlMap().then((olMap2) => {
        map$4 = olMap2;
        dragPanControl$1 = createDragPanControl(olMap2);
        if (enabled$2) {
          disableDoubleClickZoomInteractions();
          addListeners$1();
        }
      });
    },
    enable() {
      enabled$2 = true;
      addListeners$1();
      return getOlMap().then((olMap2) => {
        if (!enabled$2) return;
        map$4 = olMap2;
        disableDoubleClickZoomInteractions();
      });
    },
    disable() {
      enabled$2 = false;
      restoreDoubleClickZoomInteractions();
      removeListeners$1();
      resetGesture$1();
    }
  };
  const MODULE_ID$b = "drawButtonFix";
  let observer$1 = null;
  const drawButtonFix = {
    id: MODULE_ID$b,
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
  const MODULE_ID$a = "groupErrorToasts";
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
    id: MODULE_ID$a,
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
  const styles$3 = "#attack-slider-close{display:none!important}";
  const MODULE_ID$9 = "removeAttackCloseButton";
  const removeAttackCloseButton = {
    id: MODULE_ID$9,
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
      injectStyles(styles$3, MODULE_ID$9);
    },
    disable() {
      removeStyles(MODULE_ID$9);
    }
  };
  const MODULE_ID$8 = "keepScreenOn";
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
    id: MODULE_ID$8,
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
  function isRecord$1(value) {
    return typeof value === "object" && value !== null;
  }
  function isInventoryCore(value) {
    return isRecord$1(value) && typeof value.g === "string" && value.t === ITEM_TYPE_CORE && typeof value.l === "number" && typeof value.a === "number";
  }
  function isInventoryCatalyser(value) {
    return isRecord$1(value) && typeof value.g === "string" && value.t === ITEM_TYPE_CATALYSER && typeof value.l === "number" && typeof value.a === "number";
  }
  function isInventoryReference(value) {
    return isRecord$1(value) && typeof value.g === "string" && value.t === ITEM_TYPE_REFERENCE && typeof value.l === "string" && typeof value.a === "number";
  }
  function isInventoryReferenceFull(value) {
    if (!isInventoryReference(value)) return false;
    const record = value;
    return Array.isArray(record.c) && record.c.length === 2 && typeof record.c[0] === "number" && typeof record.c[1] === "number" && typeof record.ti === "string";
  }
  function isInventoryBroom(value) {
    return isRecord$1(value) && typeof value.g === "string" && value.t === ITEM_TYPE_BROOM && typeof value.l === "number" && typeof value.a === "number";
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
  const MODULE_ID$7 = "keyCountOnPoints";
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
  let map$3 = null;
  let pointsSource$1 = null;
  let labelsSource = null;
  let labelsLayer = null;
  let debounceTimer = null;
  let mutationObserver = null;
  let onPointsChange = null;
  let onZoomChange = null;
  function renderLabels() {
    var _a, _b, _c, _d, _e, _f, _g;
    if (!labelsSource || !map$3 || !pointsSource$1) return;
    labelsSource.clear();
    const zoom = ((_b = (_a = map$3.getView()).getZoom) == null ? void 0 : _b.call(_a)) ?? 0;
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
    id: MODULE_ID$7,
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
        map$3 = olMap2;
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
      if (map$3 && onZoomChange) {
        (_b = (_a = map$3.getView()).un) == null ? void 0 : _b.call(_a, "change:resolution", onZoomChange);
        onZoomChange = null;
      }
      if (map$3 && labelsLayer) {
        map$3.removeLayer(labelsLayer);
      }
      map$3 = null;
      pointsSource$1 = null;
      labelsSource = null;
      labelsLayer = null;
    }
  };
  const MODULE_ID$6 = "largerPointTapArea";
  const HIT_TOLERANCE_PX = 15;
  let map$2 = null;
  let originalMethod = null;
  const largerPointTapArea = {
    id: MODULE_ID$6,
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
        map$2 = olMap2;
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
      if (map$2 && originalMethod && map$2.forEachFeatureAtPixel) {
        map$2.forEachFeatureAtPixel = originalMethod;
      }
      originalMethod = null;
      map$2 = null;
    }
  };
  const styles$2 = ".info.popup .i-buttons .svp-next-point-button{position:fixed;bottom:5px;right:5px;width:32px;height:32px;min-height:auto}";
  const MODULE_ID$5 = "nextPointNavigation";
  const BUTTON_CLASS = "svp-next-point-button";
  const INTERACTION_RANGE = 45;
  const AUTOZOOM_THRESHOLD = 16;
  const AUTOZOOM_TARGET = 17;
  const AUTOZOOM_TIMEOUT_MS = 3e3;
  let map$1 = null;
  let pointsSource = null;
  let playerSource = null;
  const rangeVisited = /* @__PURE__ */ new Set();
  let expectedNextGuid = null;
  let lastSeenGuid = null;
  let fakeClickRetries = 0;
  const MAX_FAKE_CLICK_RETRIES = 3;
  let popupObserver$1 = null;
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
    if (!map$1 || typeof map$1.dispatchEvent !== "function" || typeof map$1.getPixelFromCoordinate !== "function") {
      return;
    }
    const feature = findFeatureById(guid);
    if (!feature) return;
    const coords = feature.getGeometry().getCoordinates();
    const pixel = map$1.getPixelFromCoordinate(coords);
    map$1.dispatchEvent({ type: "click", pixel, originalEvent: {} });
  }
  function tryNavigateInRange() {
    if (!map$1 || !pointsSource) return false;
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
    if (!map$1 || !pointsSource) return;
    const view = map$1.getView();
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
    if (!popup2.querySelector(`.${BUTTON_CLASS}`)) {
      const rangeButton2 = document.createElement("button");
      rangeButton2.className = BUTTON_CLASS;
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
    const rangeButton = popup2.querySelector(`.${BUTTON_CLASS}`);
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
    const rangeButton = popup2.querySelector(`.${BUTTON_CLASS}`);
    if (rangeButton) {
      rangeButton.disabled = !hasInRangePoints(getPopupPointId());
    }
  }
  function removeButton() {
    var _a;
    (_a = document.querySelector(`.${BUTTON_CLASS}`)) == null ? void 0 : _a.remove();
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
    popupObserver$1 = new MutationObserver(() => {
      onPopupMutation(popup2);
    });
    popupObserver$1.observe(popup2, {
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
      if (!map$1) return;
      startObservingPopup(element);
    });
  }
  const nextPointNavigation = {
    id: MODULE_ID$5,
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
        map$1 = olMap2;
        pointsSource = source;
        playerSource = playerLayerSource;
        injectStyles(styles$2, MODULE_ID$5);
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
      if (popupObserver$1) {
        popupObserver$1.disconnect();
        popupObserver$1 = null;
      }
      if (playerMoveHandler) {
        (_a = document.querySelector(".info")) == null ? void 0 : _a.removeEventListener("playermove", playerMoveHandler);
        playerMoveHandler = null;
      }
      if (pointsSource && sourceChangeHandler) {
        pointsSource.un("change", sourceChangeHandler);
        sourceChangeHandler = null;
      }
      removeButton();
      removeStyles(MODULE_ID$5);
      map$1 = null;
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
  const MODULE_ID$4 = "refsOnMap";
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
  async function fetchPointTeam(pointGuid) {
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
        console.error(`[SVP] ${MODULE_ID$4}: deletion error:`, response.error);
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
      console.error(`[SVP] ${MODULE_ID$4}: deletion failed:`, error);
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
          const team = await fetchPointTeam(pointGuid);
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
    const checkbox = document.querySelector("#toggle-follow");
    if (checkbox instanceof HTMLInputElement) checkbox.checked = false;
  }
  function restoreFollowMode() {
    if (beforeOpenFollow === null || beforeOpenFollow === "false") return;
    localStorage.setItem("follow", beforeOpenFollow);
    const checkbox = document.querySelector("#toggle-follow");
    if (checkbox instanceof HTMLInputElement) checkbox.checked = true;
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
    id: MODULE_ID$4,
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
      injectStyles(css, MODULE_ID$4);
      return getOlMap().then((map2) => {
        var _a, _b;
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
      });
    },
    disable() {
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
      removeStyles(MODULE_ID$4);
      teamCache.clear();
      olMap = null;
      refsSource = null;
      refsLayer = null;
    }
  };
  const MODULE_ID$3 = "repairAtFullCharge";
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
    id: MODULE_ID$3,
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
  const MODULE_ID$2 = "singleFingerRotation";
  let viewport = null;
  let map = null;
  let dragPanControl = null;
  let latestPoint = null;
  let inflateExtent = false;
  let enabled$1 = false;
  let pendingDelta = 0;
  let frameRequestId = null;
  function isFollowActive() {
    return localStorage.getItem("follow") !== "false";
  }
  function getScreenCenter() {
    const padding = (map ? map.getView().padding : void 0) ?? [0, 0, 0, 0];
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
    if (!map) return;
    const view = map.getView();
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
  const singleFingerRotation = {
    id: MODULE_ID$2,
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
      return getOlMap().then((olMap2) => {
        map = olMap2;
        dragPanControl = createDragPanControl(olMap2);
        const viewportElement = $(".ol-viewport");
        if (viewportElement instanceof HTMLElement) {
          viewport = viewportElement;
        }
        const view = olMap2.getView();
        const originalCalculateExtent = view.calculateExtent.bind(view);
        view.calculateExtent = (size) => {
          if (inflateExtent && size) {
            const diagonal = Math.ceil(Math.sqrt(size[0] ** 2 + size[1] ** 2));
            return originalCalculateExtent([diagonal, diagonal]);
          }
          return originalCalculateExtent(size);
        };
        if (enabled$1) {
          addListeners();
        }
      });
    },
    enable() {
      enabled$1 = true;
      inflateExtent = true;
      addListeners();
    },
    disable() {
      enabled$1 = false;
      inflateExtent = false;
      removeListeners();
      dragPanControl == null ? void 0 : dragPanControl.restore();
      resetGesture();
    }
  };
  const styles$1 = ".svp-tile-url-input{width:100%;box-sizing:border-box;padding:4px 6px;font-size:12px;font-family:inherit;background:var(--background);color:var(--text);border:1px solid var(--border);border-radius:4px;resize:none}.svp-tile-url-input::placeholder{color:var(--text-disabled)}";
  const MODULE_ID$1 = "mapTileLayers";
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
  let popupObserver = null;
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
    id: MODULE_ID$1,
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
        injectStyles(styles$1, MODULE_ID$1);
        const existingPopup = document.querySelector(".layers-config");
        if (existingPopup) {
          injectIntoPopup(existingPopup);
        }
        popupObserver = new MutationObserver(onMutation);
        popupObserver.observe(document.body, { childList: true });
      });
    },
    disable() {
      enabled = false;
      unlockGameSource(true);
      removeStyles(TILE_FILTER_ID);
      removeStyles(MODULE_ID$1);
      cleanupInjected();
      restoreGameRadioSelection();
      popupObserver == null ? void 0 : popupObserver.disconnect();
      popupObserver = null;
      gameTileLayer = null;
      originalSource = null;
      lastGameRadioValue = null;
    }
  };
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
  function calculateDeletions(items, limits) {
    const deletions = [];
    const coresByLevel = groupByLevel(items, ITEM_TYPE_CORE);
    addLevelDeletions(coresByLevel, limits.cores, ITEM_TYPE_CORE, deletions);
    const catalysersByLevel = groupByLevel(items, ITEM_TYPE_CATALYSER);
    addLevelDeletions(catalysersByLevel, limits.catalysers, ITEM_TYPE_CATALYSER, deletions);
    return deletions;
  }
  function groupByLevel(items, type) {
    const grouped = /* @__PURE__ */ new Map();
    for (const item of items) {
      if (item.t !== type) continue;
      if (item.a <= 0) continue;
      const level = item.l;
      const entries2 = grouped.get(level) ?? [];
      entries2.push({ guid: item.g, amount: item.a });
      grouped.set(level, entries2);
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
  const STORAGE_KEY = "svp_inventoryCleanup";
  const MIN_FREE_SLOTS_FLOOR = 20;
  function defaultLevelLimits() {
    const limits = {};
    for (let level = 1; level <= 10; level++) {
      limits[level] = -1;
    }
    return limits;
  }
  function defaultCleanupSettings() {
    return {
      version: 1,
      limits: {
        cores: defaultLevelLimits(),
        catalysers: defaultLevelLimits(),
        references: -1
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
  function isCleanupLimits(value) {
    if (!isRecord(value)) return false;
    return isLevelLimits(value.cores) && isLevelLimits(value.catalysers) && typeof value.references === "number";
  }
  function isCleanupSettings(value) {
    if (!isRecord(value)) return false;
    return typeof value.version === "number" && isCleanupLimits(value.limits) && typeof value.minFreeSlots === "number";
  }
  function loadCleanupSettings() {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return defaultCleanupSettings();
    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return defaultCleanupSettings();
    }
    if (!isCleanupSettings(parsed)) return defaultCleanupSettings();
    if (parsed.minFreeSlots < MIN_FREE_SLOTS_FLOOR) {
      parsed.minFreeSlots = MIN_FREE_SLOTS_FLOOR;
    }
    sanitizeLimits(parsed.limits);
    return parsed;
  }
  function sanitizeLevelLimits(limits) {
    for (let level = 1; level <= 10; level++) {
      if (limits[level] < -1) {
        limits[level] = 0;
      }
    }
  }
  function sanitizeLimits(limits) {
    sanitizeLevelLimits(limits.cores);
    sanitizeLevelLimits(limits.catalysers);
    if (limits.references < -1) {
      limits.references = 0;
    }
  }
  function saveCleanupSettings(settings) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
  }
  const styles = ".svp-cleanup-settings{position:fixed;top:0;right:0;bottom:0;left:0;z-index:10001;background:var(--background);color:var(--text);display:none;flex-direction:column;font-size:13px}.svp-cleanup-settings.svp-open{display:flex}.svp-cleanup-header{display:flex;justify-content:space-between;align-items:center;font-size:14px;font-weight:700;padding:4px 8px;flex-shrink:0;border-bottom:1px solid var(--border-transp);max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-content{flex:1;overflow-y:auto;padding:8px;display:flex;flex-direction:column;gap:8px;max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-section-title{font-size:10px;font-weight:600;color:var(--text);text-transform:uppercase;letter-spacing:.08em;padding:6px 0 2px;border-bottom:1px solid var(--border-transp);margin-bottom:2px}.svp-cleanup-level-grid{display:grid;grid-template-columns:1fr 1fr;gap:2px 12px}.svp-cleanup-level-cell,.svp-cleanup-row{display:flex;justify-content:space-between;align-items:center;padding:2px 0}.svp-cleanup-row-label{font-size:12px}.svp-cleanup-row-input{width:60px;padding:2px 4px;border:1px solid var(--border);border-radius:4px;background:var(--background);color:var(--text);font-size:12px;text-align:center}.svp-cleanup-footer{flex-shrink:0;padding:6px 8px 40px;border-top:1px solid var(--border-transp);display:flex;align-items:center;justify-content:flex-end;gap:8px;max-width:600px;margin-left:auto;margin-right:auto;width:100%;box-sizing:border-box}.svp-cleanup-button{background:none;border:1px solid var(--border);color:var(--text);border-radius:4px;padding:4px 12px;font-size:12px;cursor:pointer}.svp-cleanup-button-primary{background:var(--accent);color:var(--background);border-color:var(--accent)}.svp-cleanup-configure-button{background:none;border:1px solid var(--border);color:var(--text);border-radius:4px;padding:2px 6px;font-size:10px;cursor:pointer;margin-left:4px}.svp-cleanup-toast{position:fixed;top:50px;left:50%;transform:translate(-50%);background:var(--background);color:var(--text);border:1px solid var(--border);border-radius:4px;padding:6px 12px;font-size:12px;z-index:10002;opacity:1;transition:opacity .3s ease;pointer-events:none;max-width:90vw;text-align:center;box-sizing:border-box}.svp-cleanup-toast-hide{opacity:0}";
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
  let panel = null;
  let configureButton = null;
  let moduleRowObserver = null;
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
  function buildPanel(settings, onSave) {
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
  function injectConfigureButton() {
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
        configureButton = document.createElement("button");
        configureButton.className = "svp-cleanup-configure-button";
        configureButton.textContent = t(CONFIGURE_LABEL);
        configureButton.addEventListener("click", (event) => {
          event.stopPropagation();
          openSettingsPanel();
        });
        nameLine.appendChild(configureButton);
        return;
      }
    }
  }
  function openSettingsPanel() {
    if (panel) {
      panel.remove();
    }
    const settings = loadCleanupSettings();
    panel = buildPanel(settings, (updatedSettings) => {
      saveCleanupSettings(updatedSettings);
    });
    document.body.appendChild(panel);
    panel.classList.add("svp-open");
  }
  function initCleanupSettingsUi() {
    injectStyles(styles, STYLES_ID);
    injectConfigureButton();
    moduleRowObserver = new MutationObserver(() => {
      if (!document.querySelector(".svp-cleanup-configure-button")) {
        injectConfigureButton();
      }
    });
    moduleRowObserver.observe(document.body, { childList: true, subtree: true });
  }
  function destroyCleanupSettingsUi() {
    removeStyles(STYLES_ID);
    if (panel) {
      panel.remove();
      panel = null;
    }
    if (configureButton) {
      configureButton.remove();
      configureButton = null;
    }
    if (moduleRowObserver) {
      moduleRowObserver.disconnect();
      moduleRowObserver = null;
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
  const DELETABLE_TYPES = /* @__PURE__ */ new Set([ITEM_TYPE_CORE, ITEM_TYPE_CATALYSER]);
  async function deleteInventoryItems(deletions) {
    for (const entry of deletions) {
      if (!DELETABLE_TYPES.has(entry.type)) {
        throw new Error(`Удаление предметов типа ${entry.type} запрещено`);
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
  function updateInventoryCache(deletions) {
    const raw = localStorage.getItem(INVENTORY_CACHE_KEY);
    if (!raw) {
      console.warn("[SVP inventoryCleanup] inventory-cache отсутствует, пропуск обновления");
      return;
    }
    let cache;
    try {
      cache = JSON.parse(raw);
    } catch {
      console.warn("[SVP inventoryCleanup] inventory-cache содержит невалидный JSON");
      return;
    }
    if (!Array.isArray(cache)) {
      console.warn("[SVP inventoryCleanup] inventory-cache не является массивом");
      return;
    }
    for (const entry of deletions) {
      const cached = cache.find((item) => item.g === entry.guid);
      if (cached) {
        cached.a -= entry.amount;
      }
    }
    cache = cache.filter((item) => item.a > 0);
    localStorage.setItem(INVENTORY_CACHE_KEY, JSON.stringify(cache));
  }
  const MODULE_ID = "inventoryCleanup";
  const ACTION_SELECTORS = "#discover, .discover-mod";
  const TOAST_DURATION = 3e3;
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
  function showCleanupToast(message) {
    const toast = document.createElement("div");
    toast.className = "svp-cleanup-toast";
    toast.textContent = message;
    document.body.appendChild(toast);
    setTimeout(() => {
      toast.classList.add("svp-cleanup-toast-hide");
      toast.addEventListener("transitionend", () => {
        toast.remove();
      });
    }, TOAST_DURATION);
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
  function updateDomInventoryCount(total) {
    const element = document.getElementById("self-info__inv");
    if (element) {
      element.textContent = String(total);
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
    const deletions = calculateDeletions(items, settings.limits);
    if (deletions.length === 0) return;
    const totalAmount = deletions.reduce((sum, entry) => sum + entry.amount, 0);
    const summary = formatDeletionSummary(deletions);
    console.log(
      `[SVP inventoryCleanup] Удалить ${totalAmount} предметов (инвентарь: ${currentCount}/${inventoryLimit})`,
      deletions
    );
    try {
      const result = await deleteInventoryItems(deletions);
      updateInventoryCache(deletions);
      updateDomInventoryCount(result.total);
      showCleanupToast(`Очистка (${totalAmount}): ${summary}`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Неизвестная ошибка";
      console.error("[SVP inventoryCleanup] Ошибка удаления:", message);
      showCleanupToast(`Ошибка очистки: ${message}`);
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
    id: MODULE_ID,
    name: {
      en: "Inventory auto-cleanup",
      ru: "Автоочистка инвентаря"
    },
    description: {
      en: "Automatically removes excess items when discovering points",
      ru: "Автоматически удаляет лишние предметы при изучении точек"
    },
    defaultEnabled: true,
    category: "utility",
    init() {
    },
    enable() {
      document.addEventListener("click", onClickCapture, true);
      installSetItemInterceptor();
      initCleanupSettingsUi();
    },
    disable() {
      document.removeEventListener("click", onClickCapture, true);
      uninstallSetItemInterceptor();
      discoverPending = false;
      destroyCleanupSettingsUi();
    }
  };
  if (!isDisabled()) {
    let init = function() {
      initErrorLog();
      installSbgFlavor();
      bootstrap([
        enhancedMainScreen,
        enhancedPointPopupUi,
        swipeToClosePopup,
        groupErrorToasts,
        removeAttackCloseButton,
        shiftMapCenterDown,
        largerPointTapArea,
        disableDoubleTapZoom,
        ngrsZoom,
        drawButtonFix,
        keepScreenOn,
        inventoryCleanup,
        keyCountOnPoints,
        nextPointNavigation,
        refsOnMap,
        repairAtFullCharge,
        singleFingerRotation,
        mapTileLayers
      ]);
    };
    installGameScriptPatcher();
    initOlMapCapture();
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", init);
    } else {
      init();
    }
  }

})();