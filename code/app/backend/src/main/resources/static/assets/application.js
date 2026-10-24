(() => {
    "use strict";

    const form = document.getElementById("application-form");
    const field = (name) => form.elements.namedItem(name);
    const button = document.getElementById("submit-button");
    const buttonLabel = document.getElementById("submit-label");
    const errorBox = document.getElementById("submission-error");
    const errorText = document.getElementById("submission-error-text");
    const loginLink = document.getElementById("login-again");
    const workspace = document.getElementById("application-workspace");
    const success = document.getElementById("submission-success");
    const csrfHeader = document.querySelector('meta[name="csrf-header"]').content;
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    let submitting = false;
    let accepted = false;

    function moneyValue(value) {
        return value.trim().replace(/[\s\u00a0\u202f]/g, "").replace(",", ".");
    }

    // Keep monetary values as decimal strings; Number can round valid 15-digit amounts.
    function displayMoney(value) {
        const normalized = moneyValue(value);
        if (!/^\d{1,15}(\.\d{1,2})?$/.test(normalized)) return null;
        const [integer, fraction] = normalized.split(".");
        return integer.replace(/^0+(?=\d)/, "").replace(/\B(?=(\d{3})+(?!\d))/g, " ")
            + (fraction ? `,${fraction}` : "") + " ₽";
    }

    function updateSummary() {
        document.getElementById("summary-amount").textContent = displayMoney(field("requestedAmount").value) || "— ₽";
        document.getElementById("summary-term").textContent = field("termMonths").value ? `${field("termMonths").value} мес.` : "Не указан";
        const purpose = field("financingPurpose");
        document.getElementById("summary-purpose").textContent = purpose.value ? purpose.selectedOptions[0].textContent : "Не выбрана";
        document.getElementById("summary-frequency").textContent = field("paymentFrequency").value === "MONTHLY" ? "Ежемесячно" : "Ежеквартально";
        document.getElementById("summary-collateral").textContent = displayMoney(field("declaredCollateralValue").value) || "Не указана";
    }

    function setError(name, message) {
        const input = field(name);
        input.setCustomValidity(message);
        input.setAttribute("aria-invalid", String(Boolean(message)));
        const label = document.getElementById(`${name}-error`);
        if (label) {
            label.textContent = message;
            label.hidden = !message;
        }
    }

    function validateField(name) {
        const input = field(name);
        const value = input.value.trim();
        let message = "";
        if (name === "businessId") {
            if (!uuidPattern.test(value)) message = "Введите идентификатор компании в формате UUID.";
        } else if (name === "requestedAmount" || name === "declaredCollateralValue") {
            const normalized = moneyValue(value);
            if (name === "declaredCollateralValue" && !normalized) {
                setError(name, "");
                return true;
            }
            if (!/^\d{1,15}(\.\d{1,2})?$/.test(normalized)) {
                message = "Введите сумму: до 15 цифр и до двух знаков после запятой.";
            } else if (name === "requestedAmount" && !/[1-9]/.test(normalized)) {
                message = "Сумма кредита должна быть больше нуля.";
            }
        } else if (name === "termMonths") {
            if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 2147483647) {
                message = "Укажите срок целым положительным числом месяцев.";
            }
        } else if (name === "financingPurpose" && !value) {
            message = "Выберите цель финансирования.";
        }
        setError(name, message);
        return !message;
    }

    function showError(message, needsLogin = false) {
        errorText.textContent = message;
        loginLink.hidden = !needsLogin;
        errorBox.hidden = false;
        errorBox.focus();
    }

    async function postWithCsrf(url, payload) {
        // JWT authentication can clear the CSRF cookie. The existing public login
        // form issues a fresh token without authenticating or changing user data.
        const loginPage = await fetch(form.dataset.loginUrl, { credentials: "same-origin", cache: "no-store" });
        if (!loginPage.ok) throw new Error("Unable to load CSRF token");
        const document = new DOMParser().parseFromString(await loginPage.text(), "text/html");
        const csrfToken = document.querySelector('input[name="_csrf"]')?.value;
        if (!csrfToken || !csrfHeader) throw new Error("Missing CSRF token");
        return fetch(url, {
            method: "POST", credentials: "same-origin",
            headers: { [csrfHeader]: csrfToken, ...(payload ? { "Content-Type": "application/json", "Accept": "application/json" } : {}) },
            ...(payload ? { body: JSON.stringify(payload) } : {}),
        });
    }

    async function send(payload) {
        const submit = () => postWithCsrf(form.action, payload);
        let response = await submit();
        // A 401 is rejected before the command runs; only this case is safe to retry automatically.
        if (response.status === 401) {
            const refreshed = await postWithCsrf(form.dataset.refreshUrl);
            if (refreshed.ok) response = await submit();
        }
        return response;
    }

    form.noValidate = true;
    for (const name of ["businessId", "requestedAmount", "termMonths", "financingPurpose", "declaredCollateralValue"]) {
        field(name).addEventListener("blur", () => validateField(name));
        field(name).addEventListener("input", () => {
            if (field(name).getAttribute("aria-invalid") === "true") validateField(name);
        });
    }
    form.addEventListener("input", updateSummary);
    form.addEventListener("change", updateSummary);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (submitting || accepted) return;
        errorBox.hidden = true;
        const valid = ["businessId", "requestedAmount", "termMonths", "financingPurpose", "declaredCollateralValue"]
            .map(validateField).every(Boolean);
        if (!valid) {
            form.querySelector('[aria-invalid="true"]').focus();
            return;
        }
        const payload = {
            businessId: field("businessId").value.trim(),
            requestedAmount: moneyValue(field("requestedAmount").value),
            termMonths: Number(field("termMonths").value),
            paymentFrequency: field("paymentFrequency").value,
            financingPurpose: field("financingPurpose").value,
            declaredCollateralValue: moneyValue(field("declaredCollateralValue").value) || null,
        };
        submitting = true;
        button.disabled = true;
        buttonLabel.textContent = "Отправляем…";
        form.setAttribute("aria-busy", "true");
        try {
            const response = await send(payload);
            if (response.status === 202) {
                const result = await response.json();
                if (typeof result.applicationId !== "string" || !uuidPattern.test(result.applicationId)) {
                    throw new Error("Missing application identifier");
                }
                accepted = true;
                document.getElementById("application-id").textContent = result.applicationId;
                workspace.hidden = true;
                success.hidden = false;
                document.querySelector(".status-badge").hidden = true;
                document.getElementById("success-title").focus();
            } else if (response.status === 401) {
                showError("Сессия истекла. Войдите в кабинет снова, чтобы отправить заявку.", true);
            } else if (response.status === 403) {
                showError("Отправка запрещена. Проверьте права владельца или администратора компании. Если права есть, обновите страницу и повторите попытку.");
            } else if (response.status === 404) {
                setError("businessId", "Компания не найдена или у вас нет доступа. Проверьте её идентификатор.");
                showError("Не удалось найти доступную компанию. Проверьте идентификатор в форме.");
            } else if (response.status === 400 || response.status === 422) {
                showError("Сервер не принял данные. Проверьте поля заявки и попробуйте ещё раз.");
            } else {
                showError("Не удалось подтвердить приём заявки. Повторная отправка может создать ещё одну заявку.");
            }
        } catch {
            showError("Не удалось получить подтверждение от сервера. Заявка могла сохраниться. Повторная отправка может создать ещё одну заявку.");
        } finally {
            submitting = false;
            button.disabled = accepted;
            buttonLabel.textContent = "Отправить заявку";
            form.removeAttribute("aria-busy");
        }
    });

    document.getElementById("new-application").addEventListener("click", () => {
        form.reset();
        for (const name of ["businessId", "requestedAmount", "termMonths", "financingPurpose", "declaredCollateralValue"]) setError(name, "");
        accepted = false;
        button.disabled = false;
        success.hidden = true;
        workspace.hidden = false;
        document.querySelector(".status-badge").hidden = false;
        updateSummary();
        field("businessId").focus();
    });

    document.getElementById("logout-form").addEventListener("submit", async (event) => {
        event.preventDefault();
        const logoutButton = event.currentTarget.querySelector("button");
        const logoutUrl = event.currentTarget.action;
        logoutButton.disabled = true;
        try {
            const response = await postWithCsrf(logoutUrl);
            if (!response.ok) throw new Error("Logout failed");
            window.location.assign(form.dataset.loginUrl);
        } catch {
            showError("Не удалось выйти из кабинета. Проверьте подключение и повторите попытку.");
            logoutButton.disabled = false;
        }
    });
    button.disabled = false;
    updateSummary();
})();
