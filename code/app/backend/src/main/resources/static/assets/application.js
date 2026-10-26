(async () => {
    "use strict";
    const {request, text, money, json} = window.credit;
    const form = document.getElementById("application-form");
    const field = name => form.elements.namedItem(name);
    const button = document.getElementById("submit-button");
    const errorBox = document.getElementById("submission-error");
    const errorText = document.getElementById("submission-error-text");
    const editId = new URLSearchParams(location.search).get("edit");
    let products = [];
    let busy = false;
    const normalize = value => value.trim().replace(/[\s\u00a0\u202f]/g, "").replace(",", ".");
    const decimal = (name, optional = false) => {
        const value = normalize(field(name).value);
        if (optional && !value) return null;
        if (!/^\d{1,15}(\.\d{1,2})?$/.test(value)) throw new Error(text("Введите суммы без отрицательных значений, до двух знаков после запятой.", "Enter non-negative amounts with at most two decimal places."));
        return value;
    };
    function error(message) { errorText.textContent = message; errorBox.hidden = false; errorBox.focus(); }
    function summary() {
        for (const [name, source] of [["amount", "requestedAmount"], ["collateral", "declaredCollateralValue"]]) {
            const value = normalize(field(source).value);
            document.getElementById(`summary-${name}`).textContent = /^\d+(\.\d{1,2})?$/.test(value) ? money(value) : "—";
        }
        document.getElementById("summary-term").textContent = `${field("termMonths").value || "—"} ${form.dataset.monthsUnit}`;
        document.getElementById("summary-purpose").textContent = field("financingPurpose").selectedOptions[0]?.textContent || "—";
        document.getElementById("summary-frequency").textContent = form.querySelector('input[name="paymentFrequency"]:checked').closest("label").querySelector("span").textContent;
    }
    function productChanged(reset) {
        const product = products.find(item => item.id === field("productId").value);
        if (!product) return;
        field("termMonths").min = product.minMonths;
        field("termMonths").max = product.maxMonths;
        if (reset) {
            field("termMonths").value = product.minMonths;
            field("financingPurpose").value = product.purpose;
        }
        document.getElementById("product-terms").textContent = `${money(product.minAmount)} – ${money(product.maxAmount)} · ${product.minMonths}–${product.maxMonths} ${form.dataset.monthsUnit}`;
        summary();
    }
    try {
        const [companies, catalog] = await Promise.all([request("/api/credit/companies"), request("/api/credit/products")]);
        products = catalog;
        field("businessId").replaceChildren();
        for (const company of companies) {
            const option = new Option(`${company.name || company.inn} · ${company.inn}`, company.id);
            option.disabled = company.role === "VIEWER";
            field("businessId").add(option);
        }
        const writable = companies.find(company => company.role !== "VIEWER");
        if (!writable) throw new Error(text("Нет компаний с правом подачи заявки.", "No companies available for application submission."));
        field("businessId").value = writable.id;
        for (const product of products) field("productId").add(new Option(text(product.name, product.id === "equipment" ? "Equipment" : "Working capital"), product.id));
        productChanged(true);
        if (editId) {
            const view = await request(`/api/credit/applications/${encodeURIComponent(editId)}`);
            if (!["DRAFT", "NEEDS_INFO"].includes(view.state) || !view.canManage) throw new Error(text("Заявка недоступна для редактирования.", "Application cannot be edited."));
            for (const [name, value] of Object.entries(view.application)) if (field(name)) field(name).value = value ?? "";
            field("productId").value = view.productId;
            for (const [name, value] of Object.entries(view.financials || {})) field(name).value = value;
            field("businessId").disabled = true;
            productChanged(false);
        }
        button.disabled = false;
    } catch (failure) { error(failure.message); }
    field("productId").addEventListener("change", () => productChanged(true));
    field("financingPurpose").disabled = true;
    form.addEventListener("input", summary);
    form.addEventListener("change", summary);
    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy) return;
        errorBox.hidden = true;
        try {
            const financeFields = ["monthlyRevenue", "monthlyExpenses", "existingMonthlyPayment"];
            const hasFinance = financeFields.some(name => field(name).value.trim());
            const payload = {productId: field("productId").value, application: {
                businessId: field("businessId").value, requestedAmount: decimal("requestedAmount"),
                termMonths: Number(field("termMonths").value), paymentFrequency: field("paymentFrequency").value,
                financingPurpose: field("financingPurpose").value, declaredCollateralValue: decimal("declaredCollateralValue", true)
            }, financials: hasFinance ? Object.fromEntries(financeFields.map(name => [name, decimal(name)])) : null};
            const signature = JSON.stringify(payload);
            let pending;
            try { pending = JSON.parse(sessionStorage.getItem("credit-create")); } catch (_) { /* New key below. */ }
            if (!pending || pending.signature !== signature) pending = {signature, key: crypto.randomUUID()};
            sessionStorage.setItem("credit-create", JSON.stringify(pending));
            busy = true;
            button.disabled = true;
            const options = {...json(payload), method: editId ? "PUT" : "POST"};
            options.headers["Idempotency-Key"] = pending.key;
            const result = await request(editId ? `/api/credit/applications/${encodeURIComponent(editId)}` : form.action, options);
            sessionStorage.removeItem("credit-create");
            location.assign(`/app/applications/${encodeURIComponent(editId || result.applicationId)}`);
        } catch (failure) { error(failure.message); busy = false; button.disabled = false; }
    });
    document.getElementById("logout-form").addEventListener("submit", async event => {
        event.preventDefault();
        try { await request(event.currentTarget.action, {method: "POST"}); location.assign("/login"); }
        catch (failure) { error(failure.message); }
    });
})();
