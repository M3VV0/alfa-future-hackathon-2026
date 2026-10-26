(() => {
    "use strict";
    const english = document.documentElement.lang === "en";
    const text = (ru, en) => english ? en : ru;
    const errors = {
        PRODUCT_LIMITS: text("Сумма, срок или цель не соответствуют выбранному продукту.", "Amount, term or purpose does not match the product."),
        IDEMPOTENCY_CONFLICT: text("Этот запрос уже использован с другими данными. Обновите форму.", "This request key has already been used with different data."),
        INVALID_REPORT_JSON: text("Нужен JSON-отчёт с ИНН и тремя финансовыми показателями. Скачайте шаблон.", "Upload a JSON report with the tax ID and all three financial figures. Download the template."),
        INVALID_STATE_TRANSITION: text("Это действие уже недоступно. Обновите заявку.", "This action is no longer available. Refresh the application."),
        APPLICATION_IMMUTABLE: text("После отправки данные заявки изменить нельзя.", "Application data cannot be edited after submission."),
        REPORT_MAX_1_MB: text("Максимальный размер отчёта — 1 МБ.", "Maximum report size is 1 MB."),
        VALIDATION_ERROR: text("Проверьте заполнение полей и формат сумм.", "Check the form fields and monetary values.")
    };
    let refreshing = null;
    async function request(url, options = {}, retry = true) {
        const headers = {...options.headers};
        const method = options.method || "GET";
        if (method !== "GET") {
            const csrfResponse = await fetch("/auth/csrf", {credentials: "same-origin", cache: "no-store"});
            if (!csrfResponse.ok) throw new Error(text("Не удалось подготовить запрос.", "Could not prepare request."));
            const csrf = await csrfResponse.json();
            headers[csrf.headerName] = csrf.token;
        }
        let response;
        try {
            response = await fetch(url, {...options, headers, credentials: "same-origin", cache: "no-store"});
        } catch (_) {
            throw new Error(text("Нет связи с сервером. Повторите действие: сохранение черновика защищено от дублирования.", "Connection failed. Retry the action; draft creation is protected against duplicates."));
        }
        if (response.status === 401 && retry) {
            try {
                // Detail and company data load together; rotate their shared session only once.
                if (!refreshing) refreshing = request("/auth/refresh", {method: "POST"}, false).finally(() => { refreshing = null; });
                await refreshing;
            }
            catch (_) { window.location.assign("/login"); throw new Error(text("Войдите снова.", "Please sign in again.")); }
            return request(url, options, false);
        }
        if (!response.ok) {
            let code;
            try { code = (await response.json()).code; } catch (_) { /* Safe generic message below. */ }
            throw new Error(errors[code] || (response.status === 403 ? text("Недостаточно прав для этого действия.", "You do not have permission for this action.")
                : response.status === 404 ? text("Заявка или компания не найдена.", "Application or company not found.")
                : text("Не удалось выполнить действие. Обновите страницу и проверьте данные.", "Action failed. Refresh the page and check the data.")));
        }
        // Logout follows a 303 to the HTML login page; callers then navigate there.
        if (response.status === 204 || response.status === 202 || !response.headers.get("Content-Type")?.includes("application/json")) return null;
        return response.json();
    }
    window.credit = {request, text, money: value => new Intl.NumberFormat(english ? "en" : "ru", {style: "currency", currency: "RUB"}).format(value),
        json: body => ({headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)})};
})();
