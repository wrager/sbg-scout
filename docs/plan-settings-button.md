# План: двойной режим кнопки настроек SBG Scout

## Цель

Текущая маленькая кнопка в правом верхнем углу экрана заменяется на **двухрежимное решение**:

1. **Режим загрузки** (игра ещё не инициализирована) — нативная Android-кнопка «Настройки SBG Scout», в 2 раза больше текущей, по центру по горизонтали, на 20 % снизу по вертикали.
2. **Режим игры** (игра инициализирована) — нативная кнопка скрывается; в игровую панель настроек (`.settings-content`) инжектируется HTML-кнопка «Настройки SBG Scout», которая открывает тот же экран настроек приложения.

## Текущее состояние (контекст)

- [activity_game.xml:26-36](app/src/main/res/layout/activity_game.xml#L26-L36) — `ImageButton` 24×24dp в правом верхнем углу.
- [GameActivity.kt:416-434](app/src/main/java/com/github/wrager/sbgscout/GameActivity.kt#L416-L434) — `setupSettings()`: установка обработчика на кнопку.
- [GameActivity.kt:439-446](app/src/main/java/com/github/wrager/sbgscout/GameActivity.kt#L439-L446) — `openSettings()` / `closeSettings()` показывают/прячут полноэкранный `FrameLayout` с `SettingsFragment`.
- [SbgWebViewClient.kt:21-31](app/src/main/java/com/github/wrager/sbgscout/webview/SbgWebViewClient.kt#L21-L31) — инжекция JS происходит в `onPageStarted` (обёртка localStorage + пользовательские скрипты).
- Существующие бриджи: `Android` (clipboard), `__sbg_settings` (изменения настроек игры), `__sbg_share` (share).

## Структура DOM игровых настроек (из refs)

Игровая панель настроек — **статичный HTML** в [refs/game/index.html:115-188](refs/game/index.html#L115-L188):

```html
<div class="settings popup pp-center pp-mfull hidden">
  <h2 class="settings-header" data-i18n="settings.header">...</h2>
  <div class="settings-content">
    <div class="settings-section">
      <h4 class="settings-section__header" data-i18n="..."></h4>
      <label class="settings-section__item">
        <span data-i18n="..."></span>
        <select data-setting="..."></select>
      </label>
      ...
    </div>
    ...
  </div>
</div>
```

Кнопки-элементы в настройках идут по шаблону (строки 171, 175, 179-184):
```html
<div class="settings-section__item">
  <span data-i18n="...">Подпись</span>
  <button class="settings-section__button" id="...">Действие</button>
</div>
```

Контейнер `.settings-content` существует с момента загрузки DOM (онтопуль статичного HTML), но внутри есть `data-i18n` атрибуты, которые заполняются переводами через i18next асинхронно в `async main()` ([refs/game/script.js:1-60](refs/game/script.js#L1-L60)).

## Сигнал готовности игры

Игра не эмитит явных событий «ready». Кандидаты:

| Сигнал | Как проверить | Плюсы | Минусы |
|---|---|---|---|
| i18next применён | `document.querySelector('#settings').textContent !== 'menu.settings'` (кнопка в `.ol-control` получила перевод) | Просто, надёжно; как раз показывает что панель настроек пригодна к инжекции | Требует MutationObserver или polling |
| `self_data` доступен | Глобальная переменная игры, появляется после auth/загрузки | Игра полностью интерактивна | `self_data` — локальная в IIFE, недоступна снаружи (в `window` не попадает) |
| Текст `#version` ≠ "—" | Версия заполняется после fetch'а | Простой DOM-сигнал | Поздний; не гарантирует UI-готовность |
| `.self-info__name` ≠ "?" | Имя игрока загружается после auth | Полная готовность UI | Самый поздний |

**Выбор**: применение i18next (`#settings` получил перевод). Это минимальный достаточный сигнал — переводы готовы → можно безопасно вставлять локализованный текст и ожидать, что рядом тоже будут переводы. MutationObserver на `#settings` с проверкой `textContent`.

Альтернатива, более прямолинейная: наблюдать непосредственно за `.settings-section__header` первого заголовка (`data-i18n="settings.global.header"`), ждать пока `textContent` перестанет совпадать с ключом.

## Архитектура решения

### Компоненты

1. **Большая кнопка загрузки** — `ImageButton` (или `MaterialButton`) с текстом «Настройки SBG Scout», 48dp высота (2× от 24dp), `layout_gravity="bottom|center_horizontal"`, позиция считается программно (20 % от высоты экрана снизу).

2. **Мини-кнопка правого-верхнего угла** — **удаляется** (вся логика перетекает либо в большую кнопку, либо в инжектированную HTML-кнопку).

3. **HTML-кнопка внутри игровой панели** — `button.settings-section__button` с id `sbg-scout-settings`, добавляется в начало `.settings-content` (или в конец — см. открытые вопросы).

4. **GameReadyBridge** — новый `@JavascriptInterface`, который вызывается инжектированным скриптом при обнаружении готовности игры:
   - `onGameReady()` — скрыть большую кнопку
   - `openScoutSettings()` — открыть экран настроек SBG Scout (аналог тапа по старой кнопке)

### Поток взаимодействия

```
onPageStarted
  → инжектируется bootstrap-скрипт:
    • создаёт MutationObserver на #settings
    • при готовности:
       - вызывает __sbg_scout.onGameReady()
       - вставляет HTML-кнопку в .settings-content
       - навешивает click → __sbg_scout.openScoutSettings()
  → (параллельно) инжектируются пользовательские скрипты

Android:
  • На старте видна большая кнопка (по центру, 20 % снизу)
  • __sbg_scout.onGameReady() → скрыть большую кнопку
  • __sbg_scout.openScoutSettings() → openSettings() (показать FrameLayout с SettingsFragment)
  • closeSettings() (кнопка «Назад» или из фрагментов) ничего не трогает у большой кнопки, потому что она уже скрыта
```

### Поведение при перезагрузке игры (reload)

Когда пользователь жмёт «Перезагрузить игру» в настройках SBG Scout, `webView.loadUrl(GAME_URL)` вызывает новый `onPageStarted`. Бридж должен:

1. **Показать большую кнопку снова** — перед `loadUrl` делать `settingsButton.visibility = VISIBLE`. Либо слушать `onPageStarted` в `SbgWebViewClient` и уведомлять Activity через callback.
2. Инжектированный bootstrap-скрипт снова дождётся готовности и вызовет `onGameReady()` → кнопка снова скроется.

**Выбор**: уведомление из `SbgWebViewClient.onPageStarted` через callback в `GameActivity` → `showBigButton()`.

### Поведение провизионинга

Пока идёт provisioning (скачивание скриптов на первом запуске), WebView пуст. Большая кнопка должна быть **скрыта** — она имеет смысл только когда идёт загрузка игры. В `startProvisioning()` уже делается `settingsButton.visibility = GONE`, в `finishProvisioning()` — `VISIBLE`. Сохранить это поведение для большой кнопки.

## HTML-кнопка в настройках игры

### Стилизация

Использовать существующий класс `.settings-section__button` и CSS custom properties игры (`--background`, `--text`, `--border`). Кнопка автоматически подхватит тему (light/dark).

### DOM-шаблон (вставляется в `.settings-content`, в начало)

```html
<div class="settings-section" id="sbg-scout-section">
  <h4 class="settings-section__header">SBG Scout</h4>
  <div class="settings-section__item">
    <span>Настройки приложения</span>
    <button class="settings-section__button" id="sbg-scout-settings-btn">Открыть</button>
  </div>
</div>
```

### Локализация текста

Игра поддерживает `data-i18n` через i18next. Но добавлять ключи в i18next-словарь игры невозможно (это чужие файлы). Варианты:

- **Жёстко вшить текст** (рус/англ/…) — инжектировать локализованные строки в зависимости от `getSettings('lang')` из localStorage игры.
- **Всегда на русском** — бренд приложения и так русский.

**Выбор**: локализованные строки, зависящие от `localStorage.getItem('settings').lang` (или от `navigator.language` в fallback). Вариант «всегда русский» тоже приемлем для MVP.

## Изменения в коде

### Новые файлы

1. **`app/src/main/java/com/github/wrager/sbgscout/bridge/ScoutBridge.kt`**
   - JS-интерфейс `__sbg_scout`
   - Методы `onGameReady()`, `openScoutSettings()`
   - Константа `BOOTSTRAP_SCRIPT` — JS-код bootstrap (MutationObserver + инжекция HTML-кнопки)

### Модифицируемые файлы

2. **`app/src/main/res/layout/activity_game.xml`**
   - Заменить маленький `ImageButton` на большую кнопку (48dp высота, layout_gravity=`bottom|center_horizontal`)
   - Ширина — `wrap_content` с padding'ами
   - Позиционирование по Y: программно в коде (нужно 20 % от высоты rootLayout)

3. **`app/src/main/java/com/github/wrager/sbgscout/GameActivity.kt`**
   - Регистрация `ScoutBridge` через `addJavascriptInterface`
   - Обработчики бриджа: `runOnUiThread { hideBigButton() }` и `runOnUiThread { openSettings() }`
   - `doOnLayout` на rootLayout для расчёта 20 % снизу → установка `translationY` большой кнопки
   - Показ большой кнопки при старте, `finishProvisioning()`, и при reload (см. ниже)

4. **`app/src/main/java/com/github/wrager/sbgscout/webview/SbgWebViewClient.kt`**
   - Инжекция `ScoutBridge.BOOTSTRAP_SCRIPT` в `onPageStarted` (после `LOCAL_STORAGE_WRAPPER`, до пользовательских скриптов)
   - Callback `onGamePageStarted` → `GameActivity` показывает большую кнопку

5. **`docs/architecture.md`**
   - Обновить описание UI: двойной режим кнопки настроек, новый бридж `__sbg_scout`, инжектированный bootstrap-скрипт
   - Добавить `ScoutBridge.kt` в дерево файлов

### Удаления

- Старая мини-кнопка в layout (заменяется большой)

## Детали реализации бриджа

### Bootstrap-скрипт (инжектируется в `onPageStarted`)

```javascript
(function() {
  if (window.__sbg_scout_bootstrapped) return;
  window.__sbg_scout_bootstrapped = true;

  function injectButton() {
    if (document.getElementById('sbg-scout-section')) return;
    var content = document.querySelector('.settings-content');
    if (!content) return;
    var section = document.createElement('div');
    section.className = 'settings-section';
    section.id = 'sbg-scout-section';
    section.innerHTML =
      '<h4 class="settings-section__header">SBG Scout</h4>' +
      '<div class="settings-section__item">' +
        '<span>Настройки приложения</span>' +
        '<button class="settings-section__button" id="sbg-scout-settings-btn">Открыть</button>' +
      '</div>';
    content.insertBefore(section, content.firstChild);
    document.getElementById('sbg-scout-settings-btn')
      .addEventListener('click', function() {
        if (window.__sbg_scout) __sbg_scout.openScoutSettings();
      });
  }

  function checkReady() {
    var settingsBtn = document.querySelector('#settings');
    if (!settingsBtn) return false;
    var text = (settingsBtn.textContent || '').trim();
    // i18next ещё не отработал — текст равен ключу
    if (!text || text === 'menu.settings') return false;
    return true;
  }

  function onReady() {
    if (window.__sbg_scout) __sbg_scout.onGameReady();
    injectButton();
  }

  if (checkReady()) {
    onReady();
    return;
  }

  var observer = new MutationObserver(function() {
    if (checkReady()) {
      observer.disconnect();
      onReady();
    }
  });
  observer.observe(document.documentElement, {
    childList: true, subtree: true, characterData: true
  });
})();
```

### Android-сторона

```kotlin
class ScoutBridge(
    private val onReady: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    @JavascriptInterface
    fun onGameReady() = onReady()

    @JavascriptInterface
    fun openScoutSettings() = onOpenSettings()

    companion object {
        const val JS_INTERFACE_NAME = "__sbg_scout"
        val BOOTSTRAP_SCRIPT = """...""".trimIndent()
    }
}
```

В `GameActivity.setupWebView()`:

```kotlin
val scoutBridge = ScoutBridge(
    onReady = { runOnUiThread { hideBigButton() } },
    onOpenSettings = { runOnUiThread { openSettings() } },
)
webView.addJavascriptInterface(scoutBridge, ScoutBridge.JS_INTERFACE_NAME)
```

В `SbgWebViewClient.onPageStarted`:

```kotlin
view.evaluateJavascript(GameSettingsBridge.LOCAL_STORAGE_WRAPPER) {}
view.evaluateJavascript(ScoutBridge.BOOTSTRAP_SCRIPT) {}
onGamePageStarted?.invoke()  // → GameActivity.showBigButton()
scriptInjector.inject(view) { ... }
```

## Позиционирование большой кнопки

- Размер: 48dp высота (2× от 24dp), ширина = `wrap_content` + горизонтальные padding'и (по дизайну — минимум 16dp с каждой стороны).
- `layout_gravity="bottom|center_horizontal"`
- Позиция Y от низа: `rootLayout.height * 0.20f` (20 % снизу). Применяется через `translationY` (отрицательное значение) или через `marginBottom`, вычисляемый в `doOnLayout`.
- Учёт edge-to-edge: rootLayout уже получает top/bottom/left/right padding через `setupWindowInsets()` в не-fullscreen режиме. Кнопка внутри rootLayout, 20 % считается от visible-области (height уже с учётом padding).

## Открытые вопросы

1. **Позиция HTML-кнопки в панели игровых настроек** — в начале `.settings-content` (первая секция) или в конце (последняя секция рядом с «О приложении»)? По UX: в конце, как «настройки оболочки» после настроек самой игры.

2. **Текст большой кнопки** — «Настройки SBG Scout» или иконка приложения + подпись? По текущему запросу — текстовая кнопка.

3. **Таймаут MutationObserver** — что если i18next не загрузился (нет сети для `i18n/meta.json`)? Нужен fallback: таймаут, например 30 секунд → инжектировать кнопку с fallback-текстом без ожидания или вообще не инжектировать (оставить большую кнопку).

4. **При открытии HTML-кнопки в настройках игры** — панель `.settings` при этом уже открыта (пользователь видит её). Нужно ли её закрыть при открытии экрана настроек SBG Scout? По UX: не закрывать — Android-экран перекроет всё.

5. **Поведение при provisioning** — большая кнопка скрыта (как сейчас); после provisioning показывается. Учтено.

6. **Reload игры** — после `webView.loadUrl(GAME_URL)` нужно показать большую кнопку до следующего `onGameReady`. Решено через callback `onGamePageStarted` в `SbgWebViewClient`.

7. **Нужен ли retry-сценарий** — если по какой-то причине HTML-кнопка не инжектировалась (флаг `__sbg_scout_bootstrapped` уже true, но `#sbg-scout-section` почему-то пропал)? MutationObserver можно оставить активным и переинжектировать, но это усложнение. Для MVP — не усложнять.
