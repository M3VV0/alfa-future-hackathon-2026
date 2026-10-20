(() => {
  "use strict";
  const body = document.body;
  const menu = document.querySelector(".menu-button");
  function closeMenu() {
    body.classList.remove("nav-open");
    menu?.setAttribute("aria-expanded", "false");
    const scrim = document.querySelector(".nav-scrim");
    if (scrim) scrim.hidden = true;
  }
  menu?.addEventListener("click", () => {
    const open = !body.classList.contains("nav-open");
    body.classList.toggle("nav-open", open);
    menu.setAttribute("aria-expanded", String(open));
    document.querySelector(".nav-scrim").hidden = !open;
  });
  document.querySelector(".nav-scrim")?.addEventListener("click", closeMenu);
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && body.classList.contains("nav-open")) {
      closeMenu();
      menu?.focus();
    }
  });
  document.querySelector(".password-toggle")?.addEventListener("click", (e) => {
    const button = e.currentTarget;
    const input = document.getElementById(button.getAttribute("aria-controls"));
    const show = input.type === "password";
    input.type = show ? "text" : "password";
    button.setAttribute(
      "aria-label",
      show ? "Скрыть пароль" : "Показать пароль",
    );
    button.setAttribute("aria-pressed", String(show));
  });
  let dirty = false;
  document.addEventListener("input", (e) => {
    if (
      e.target.closest("#draft-form") ||
      (e.target.name === "documentIds" &&
        e.target.getAttribute("form") === "draft-form")
    )
      dirty = true;
    if (e.target.type === "file") {
      const file = e.target.files[0];
      e.target.setCustomValidity(
        file && file.size > 10 * 1024 * 1024 ? "Выберите файл до 10 МБ." : "",
      );
      if (file?.name.toLowerCase().endsWith(".pdf")) {
        const kind = e.target.form?.querySelector("[name=kind]");
        if (kind) kind.value = "OTHER";
      }
    }
  });
  document.addEventListener("change", (e) => {
    if (e.target.name === "documentIds") dirty = true;
  });
  document.addEventListener(
    "submit",
    (e) => {
      if (e.target.matches("[data-requires-saved]") && dirty) {
        e.preventDefault();
        e.stopImmediatePropagation();
        const note = document.querySelector(".unsaved-warning");
        if (note) {
          note.hidden = false;
          note.scrollIntoView({ block: "center", behavior: "smooth" });
        }
        return;
      }
      if (e.target.matches(".login-form")) {
        const submit = e.target.querySelector("[type=submit]");
        if (submit) {
          submit.classList.add("is-loading");
          submit.disabled = true;
        }
      }
    },
    true,
  );
  document.addEventListener("focusout", (e) => {
    if (!e.target.matches("[data-money]")) return;
    const raw = e.target.value
      .replace(/[ \u00a0\u202f]/g, "")
      .replace(",", ".");
    if (/^\d+(\.\d{1,2})?$/.test(raw) && raw.length <= 16) {
      const [integer, fraction] = raw.split(".");
      e.target.value =
        integer.replace(/\B(?=(\d{3})+(?!\d))/g, " ") +
        (fraction ? "," + fraction : "");
    }
  });
  if (!window.htmx) return;
  htmx.config.allowEval = false;
  htmx.config.allowScriptTags = false;
  htmx.config.includeIndicatorStyles = false;
  document.addEventListener("htmx:configRequest", (e) => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content;
    if (token && header) e.detail.headers[header] = token;
  });
  document.addEventListener("htmx:beforeRequest", (e) => {
    const form = e.detail.elt.closest("form");
    if (form)
      form.querySelectorAll("button[type=submit]").forEach((button) => {
        button.disabled = true;
        button.classList.add("is-loading");
      });
  });
  document.addEventListener("htmx:afterRequest", (e) => {
    const form = e.detail.elt.closest("form");
    if (form)
      form.querySelectorAll("button[type=submit]").forEach((button) => {
        button.disabled = false;
        button.classList.remove("is-loading");
      });
  });
  document.addEventListener("htmx:beforeSwap", (e) => {
    const status = e.detail.xhr.status;
    if ([409, 422, 503].includes(status)) {
      e.detail.shouldSwap = true;
      e.detail.isError = false;
    }
    if (status === 403) {
      e.detail.shouldSwap = false;
      showFeedback(
        "Сессия формы истекла. Обновите страницу и повторите действие.",
      );
    }
  });
  document.addEventListener("htmx:afterSwap", (e) => {
    if (e.detail.target.id === "documents-panel") dirty = true;
    if (e.detail.xhr?.status >= 400) {
      const error = document.querySelector(
        '[aria-invalid="true"], #form-feedback .notice',
      );
      error?.focus();
      if (e.detail.target.id === "form-feedback")
        e.detail.target.scrollIntoView({ block: "start", behavior: "smooth" });
    }
  });
  document.addEventListener("htmx:sendError", () =>
    showFeedback(
      "Нет соединения с сервером. Введённые данные остаются в форме. Проверьте подключение и повторите действие.",
    ),
  );
  document.addEventListener("htmx:timeout", () =>
    showFeedback(
      "Сервер отвечает дольше обычного. Подождите немного и повторите действие.",
    ),
  );
  function showFeedback(message) {
    const target = document.getElementById("form-feedback");
    if (!target) return;
    const notice = document.createElement("div");
    notice.className = "notice notice-warning";
    notice.setAttribute("role", "alert");
    notice.textContent = message;
    target.replaceChildren(notice);
  }
})();
