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
    const submitLabel = buttonLabel.textContent;
    const emptySummary = Object.fromEntries(
        ["amount", "term", "purpose", "collateral"].map(name => [
            name, document.getElementById(`summary-${name}`).textContent
        ])
    );
    const moneyFormatter = new Intl.NumberFormat(document.documentElement.lang, {
        style: "currency", currency: "RUB", minimumFractionDigits: 0, maximumFractionDigits: 2
    });
    const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    const validatedFieldNames = ["businessId", "requestedAmount", "termMonths", "financingPurpose", "declaredCollateralValue"];
    const moneyPattern = /^\d{1,15}(\.\d{1,2})?$/;
    let submitting = false;
    let accepted = false;

    function moneyValue(value) {
        return value.trim().replace(/[\s\u00a0\u202f]/g, "").replace(",", ".");
    }

    // Keep monetary values as decimal strings; Number can round valid 15-digit amounts.
    function displayMoney(value) {
        const normalized = moneyValue(value);
        if (!moneyPattern.test(normalized)) return null;
        return moneyFormatter.format(normalized);
    }

    function updateSummary() {
        document.getElementById("summary-amount").textContent = displayMoney(field("requestedAmount").value) || emptySummary.amount;
        document.getElementById("summary-term").textContent = field("termMonths").value ? `${field("termMonths").value} ${form.dataset.monthsUnit}` : emptySummary.term;
        const purpose = field("financingPurpose");
        document.getElementById("summary-purpose").textContent = purpose.value ? purpose.selectedOptions[0].textContent : emptySummary.purpose;
        document.getElementById("summary-frequency").textContent = form.querySelector('input[name="paymentFrequency"]:checked').closest("label").querySelector("span").textContent;
        document.getElementById("summary-collateral").textContent = displayMoney(field("declaredCollateralValue").value) || emptySummary.collateral;
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
            if (!uuidPattern.test(value)) message = input.dataset.invalidMessage;
        } else if (name === "requestedAmount" || name === "declaredCollateralValue") {
            const normalized = moneyValue(value);
            if (name === "declaredCollateralValue" && !normalized) {
                setError(name, "");
                return true;
            }
            if (!moneyPattern.test(normalized)) {
                message = input.dataset.invalidMessage;
            } else if (name === "requestedAmount" && !/[1-9]/.test(normalized)) {
                message = input.dataset.positiveMessage;
            }
        } else if (name === "termMonths") {
            if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 2147483647) {
                message = input.dataset.invalidMessage;
            }
        } else if (name === "financingPurpose" && !value) {
            message = input.dataset.invalidMessage;
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
        const response = await fetch(form.dataset.csrfUrl, {
            credentials: "same-origin", cache: "no-store", headers: {"Accept": "application/json"}
        });
        if (!response.ok) throw new Error(`CSRF token request failed: HTTP ${response.status}`);
        const {headerName, token} = await response.json();
        if (typeof headerName !== "string" || !headerName || typeof token !== "string" || !token) {
            throw new Error("Invalid CSRF token response");
        }
        return fetch(url, {
            method: "POST", credentials: "same-origin",
            headers: {
                [headerName]: token, ...(payload ? {
                    "Content-Type": "application/json",
                    "Accept": "application/json"
                } : {})
            },
            ...(payload ? {body: JSON.stringify(payload)} : {}),
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
    for (const name of validatedFieldNames) {
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
        const valid = validatedFieldNames
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
        const editableFields = [...form.querySelectorAll("input:enabled, select:enabled")];
        submitting = true;
        for (const input of editableFields) input.disabled = true;
        button.disabled = true;
        buttonLabel.textContent = button.dataset.submittingLabel;
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
                showError(form.dataset.sessionExpired, true);
            } else if (response.status === 403) {
                showError(form.dataset.forbidden);
            } else if (response.status === 404) {
                setError("businessId", form.dataset.businessUnavailable);
                showError(form.dataset.businessNotFound);
            } else if (response.status === 400 || response.status === 422) {
                showError(form.dataset.invalidRequest);
            } else {
                showError(form.dataset.unconfirmed);
            }
        } catch (error) {
            console.error("Credit application submission failed", error);
            showError(form.dataset.networkError);
        } finally {
            submitting = false;
            for (const input of editableFields) input.disabled = false;
            button.disabled = accepted;
            buttonLabel.textContent = submitLabel;
            form.removeAttribute("aria-busy");
        }
    });

    document.getElementById("new-application").addEventListener("click", () => {
        form.reset();
        for (const name of validatedFieldNames) setError(name, "");
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
            if (!response.ok) throw new Error(`Logout failed: HTTP ${response.status}`);
            window.location.assign(form.dataset.loginUrl);
        } catch (error) {
            console.error("Logout failed", error);
            showError(form.dataset.logoutError);
            logoutButton.disabled = false;
        }
    });
    button.disabled = false;
    updateSummary();
})();
