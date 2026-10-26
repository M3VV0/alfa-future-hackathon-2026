(async () => {
    "use strict";
    const {request, text, money, json} = window.credit;
    const root = document.getElementById("cabinet-content");
    const errorBox = document.getElementById("cabinet-error");
    const id = location.pathname.match(/^\/app\/applications\/([0-9a-f-]{36})$/i)?.[1];
    const companiesPage = location.pathname === "/app/companies";
    const states = {
        DRAFT: ["Черновик", "Draft"], NEEDS_INFO: ["Нужны сведения", "Information required"], SUBMITTED: ["Ожидает рассмотрения", "Awaiting review"],
        SCORING: ["На рассмотрении", "Under review"], OFFERED: ["Предложены условия", "Offer available"], REJECTED: ["Отказ", "Rejected"],
        FAILED: ["Ошибка обработки", "Processing error"], ACCEPTED: ["Условия приняты", "Offer accepted"], SIGNED_SIMULATED: ["Подписание смоделировано", "Signing simulated"],
        DISBURSED_SIMULATED: ["Выдача смоделирована", "Disbursement simulated"], DECLINED: ["Вы отказались от предложения", "Offer declined"],
        CANCELLED: ["Заявка отменена", "Cancelled"], EXPIRED: ["Срок предложения истёк", "Offer expired"]
    };
    const reasons = {
        DRAFT_CREATED: ["Заполните сведения, приложите отчёт и отправьте заявку.", "Complete the details, upload a report and submit."],
        DRAFT_UPDATED: ["Изменения сохранены. Проверьте отчёт перед отправкой.", "Changes saved. Check your report before submitting."],
        COMPANY_DATA_REQUIRED: ["Заполните дату регистрации и статус компании в разделе «Компании».", "Complete the registration date and company status under Companies."],
        FINANCIALS_REQUIRED: ["Добавьте выручку, расходы и платежи по текущим обязательствам.", "Add revenue, expenses and existing debt payments."],
        FINANCIAL_REPORT_REQUIRED: ["Загрузите финансовый отчёт по шаблону.", "Upload a financial report using the template."],
        REPORT_MISMATCH: ["ИНН и показатели в отчёте должны совпадать с компанией и заявкой.", "The report tax ID and financial figures must match the company and application."],
        AWAITING_SCORER: ["Дополнительных действий не требуется. Результат появится здесь после рассмотрения.", "No further action is needed. Your result will appear here after review."],
        SCORING: ["Заявка рассматривается. Статус обновляется автоматически.", "Your application is being reviewed. Status updates automatically."],
        SCORER_DECLINED: ["По результатам оценки финансирование не предложено.", "Financing was not offered following assessment."],
        BUSINESS_NOT_ACTIVE: ["Компания должна иметь действующий статус.", "The company must have active registration status."],
        BUSINESS_TOO_YOUNG: ["Срок работы компании меньше минимального для этого продукта.", "The business is younger than the product minimum."],
        INSUFFICIENT_INCOME: ["По указанным доходам и обязательствам доступная сумма ниже минимума продукта.", "Based on income and existing debt, the affordable amount is below the product minimum."],
        TERMS_ADJUSTED: ["Предложены изменённые условия с учётом платёжной нагрузки.", "Adjusted terms reflect your payment capacity."],
        APPROVED_CONDITIONS: ["Ознакомьтесь с условиями и примите предложение либо откажитесь от него.", "Review the terms and accept or decline the offer."],
        SCORER_OFFER: ["Для заявки рассчитаны условия финансирования.", "Financing terms have been calculated for your application."],
        OFFER_EXPIRED: ["Создайте новую заявку для повторного рассмотрения.", "Create a new application for another review."],
        REPORT_UPLOADED: ["Загружен финансовый отчёт.", "Financial report uploaded."],
        ACCEPTED: ["Предложение принято. Далее доступна имитация подписания.", "Offer accepted. Signing simulation is available next."],
        SIGNED_SIMULATED: ["Подписание смоделировано. Реальная подпись не создавалась.", "Signing was simulated. No legal signature was created."],
        DISBURSED_SIMULATED: ["Сценарий завершён. Выдача смоделирована, деньги не перечислялись.", "Scenario complete. Disbursement was simulated; no funds were transferred."]
    };
    const label = value => states[value] ? text(...states[value]) : value;
    const explanation = value => reasons[value] ? text(...reasons[value]) : value?.startsWith("SCORER_")
        ? text("Рассмотрение временно недоступно. Это техническая ошибка, а не кредитный отказ.", "Review is temporarily unavailable. This is a technical error, not a credit rejection.") : "";
    function node(tag, content, className) {
        const element = document.createElement(tag);
        if (content !== undefined) element.textContent = content;
        if (className) element.className = className;
        return element;
    }
    function link(content, url, className = "button button-secondary") { const a = node("a", content, className); a.href = url; return a; }
    function error(failure) { errorBox.textContent = failure.message; errorBox.hidden = false; errorBox.focus(); }
    async function command(work) {
        root.querySelectorAll("button").forEach(button => button.disabled = true);
        errorBox.hidden = true;
        try { await work(); await load(); }
        catch (failure) { error(failure); root.querySelectorAll("button").forEach(button => button.disabled = false); }
    }
    function button(content, work) {
        const element = node("button", content, "button button-primary");
        element.type = "button";
        element.addEventListener("click", () => command(work));
        return element;
    }
    function detail(parent, name, value) {
        const row = node("div"); row.append(node("dt", name), node("dd", value)); parent.append(row);
    }
    function template(view, companies) {
        if (!view.financials) throw new Error(text("Сначала заполните финансовые показатели в заявке.", "Complete the financial figures first."));
        const company = companies.find(item => item.id === view.application.businessId);
        const blob = new Blob([JSON.stringify({inn: company.inn, financials: view.financials}, null, 2)], {type: "application/json"});
        const url = URL.createObjectURL(blob);
        const download = document.createElement("a"); download.href = url; download.download = "financial-report.json"; download.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    async function renderApplication() {
        const [view, companies] = await Promise.all([request(`/api/credit/applications/${id}`), request("/api/credit/companies")]);
        root.replaceChildren(node("h1", text("Заявка на финансирование", "Financing application")), node("p", id, "muted"));
        const card = node("section", undefined, "application-card dossier-card");
        card.append(node("h2", label(view.state)), node("p", explanation(view.reasonCode), "muted"));
        const terms = node("dl", undefined, "summary-list");
        const company = companies.find(item => item.id === view.application.businessId);
        detail(terms, text("Компания", "Company"), company?.name || view.application.businessId);
        detail(terms, text("Запрошенная сумма", "Requested amount"), money(view.application.requestedAmount));
        detail(terms, text("Срок", "Term"), `${view.application.termMonths} ${text("мес.", "months")}`);
        if (view.revision) detail(terms, text("Версия данных", "Input revision"), view.revision);
        card.append(terms);
        if (view.financials) {
            const finance = node("dl", undefined, "summary-list");
            detail(finance, text("Выручка в месяц", "Monthly revenue"), money(view.financials.monthlyRevenue));
            detail(finance, text("Расходы в месяц", "Monthly expenses"), money(view.financials.monthlyExpenses));
            detail(finance, text("Текущие платежи в месяц", "Existing monthly payments"), money(view.financials.existingMonthlyPayment));
            card.append(node("h3", text("Финансы", "Financials")), finance);
        }
        if (view.decision?.amount) {
            const offer = view.decision;
            const terms = node("dl", undefined, "summary-list");
            detail(terms, text("Предложенная сумма", "Offered amount"), money(offer.amount));
            detail(terms, text("Срок", "Term"), `${offer.months} ${text("мес.", "months")}`);
            detail(terms, text("Годовая ставка", "Annual rate"), `${offer.annualRate}%`);
            detail(terms, offer.paymentFrequency === "QUARTERLY" ? text("Платёж в квартал", "Quarterly payment") : text("Платёж в месяц", "Monthly payment"), money(offer.payment));
            detail(terms, text("Действует до", "Valid until"), new Date(offer.expiresAt).toLocaleString());
            card.append(node("h3", text("Условия финансирования", "Financing terms")), terms);
        }
        if (view.legacy) card.append(node("pre", JSON.stringify(view.scorerResult || {}, null, 2)));
        if (view.canManage && !view.legacy) {
            const actions = node("div", undefined, "cabinet-actions");
            const action = name => request(`/api/credit/applications/${id}/actions/${name}`, {method: "POST"});
            if (["DRAFT", "NEEDS_INFO"].includes(view.state)) {
                actions.append(link(text("Изменить сведения", "Edit details"), `/app?edit=${id}`));
                actions.append(button(text("Отправить на рассмотрение", "Submit for review"), () => request(`/api/credit/applications/${id}/submit`, {method: "POST"})));
                const upload = node("form", undefined, "report-upload");
                const title = node("label", text("Финансовый отчёт JSON, до 1 МБ", "Financial report JSON, up to 1 MB"));
                const file = node("input"); file.type = "file"; file.accept = ".json,application/json"; file.required = true;
                title.append(file);
                const send = node("button", text("Загрузить отчёт", "Upload report"), "button button-secondary"); send.type = "submit";
                upload.append(title, send);
                upload.addEventListener("submit", event => { event.preventDefault(); command(async () => {
                    if (!file.files[0] || file.files[0].size > 1048576) throw new Error(text("Выберите JSON-файл размером до 1 МБ.", "Choose a JSON file up to 1 MB."));
                    const data = new FormData(); data.append("file", file.files[0]);
                    await request(`/api/credit/applications/${id}/documents`, {method: "POST", body: data});
                }); });
                card.append(node("h3", text("Документы", "Documents")), node("p", text("Отчёт должен содержать ИНН и показатели из заявки. Шаблон заполняется текущими значениями; проверьте их перед загрузкой.", "The report must contain the company tax ID and application figures. The template uses your current figures; verify them before uploading.")), upload);
                const download = node("button", text("Скачать шаблон отчёта", "Download report template"), "button button-secondary"); download.type = "button";
                download.addEventListener("click", () => { try { template(view, companies); } catch (failure) { error(failure); } });
                card.append(download);
            }
            if (view.state === "OFFERED") actions.append(button(text("Принять условия", "Accept offer"), () => action("accept")), button(text("Отказаться", "Decline offer"), () => action("decline")));
            if (view.state === "ACCEPTED") actions.append(button(text("Имитировать подписание", "Simulate signing"), () => action("sign")));
            if (view.state === "SIGNED_SIMULATED") actions.append(button(text("Имитировать выдачу", "Simulate disbursement"), () => action("disburse")));
            if (["DRAFT", "NEEDS_INFO", "SUBMITTED", "SCORING", "FAILED"].includes(view.state)) actions.append(button(text("Отменить заявку", "Cancel application"), () => action("cancel")));
            card.append(actions);
        }
        for (const document of view.documents) card.append(link(`${text("Финансовый отчёт", "Financial report")} · v${document.revision}`, `/api/credit/applications/${id}/documents/${document.id}`, "document-link"));
        root.append(card);
        const history = node("section", undefined, "application-card dossier-card"); history.append(node("h2", text("История заявки", "Application history")));
        const list = node("ol");
        for (const entry of view.history) list.append(node("li", `${new Date(entry.createdAt).toLocaleString()} · ${label(entry.state)} · v${entry.revision}. ${explanation(entry.reasonCode)}`));
        history.append(list); root.append(history);
        if (["SUBMITTED", "SCORING"].includes(view.state)) timer = setTimeout(load, 3000);
    }
    async function renderCompanies() {
        const companies = await request("/api/credit/companies");
        root.replaceChildren(node("h1", text("Компании", "Companies")));
        for (const company of companies) {
            const card = node("section", undefined, "application-card dossier-card");
            card.append(node("h2", company.name || company.inn), node("p", `${text("ИНН", "Tax ID")}: ${company.inn}`));
            const form = node("form", undefined, "company-form");
            for (const [name, title, type, value] of [["name", text("Название", "Name"), "text", company.name], ["registeredDate", text("Дата регистрации", "Registration date"), "date", company.registeredDate]]) {
                const label = node("label", title); const input = node("input"); input.name = name; input.type = type; input.value = value || ""; input.required = true; label.append(input); form.append(label);
            }
            const label = node("label", text("Заявленный статус регистрации", "Declared registration status"));
            const status = node("select"); status.name = "registrationStatus";
            for (const [value, ru, en] of [["", "Выберите статус", "Choose status"], ["ACTIVE", "Действует", "Active"], ["REORGANIZING", "Реорганизация", "Reorganizing"], ["TERMINATING", "Прекращает деятельность", "Terminating"], ["TERMINATED", "Прекращена", "Terminated"]]) status.add(new Option(text(ru, en), value));
            status.value = company.registrationStatus || ""; status.required = true; label.append(status); form.append(label);
            const save = node("button", text("Сохранить", "Save"), "button button-primary"); save.type = "submit"; form.append(save);
            if (company.role === "VIEWER") form.querySelectorAll("input,select,button").forEach(element => element.disabled = true);
            form.addEventListener("submit", event => { event.preventDefault(); command(() => request(`/api/credit/companies/${company.id}`, {method: "PUT", ...json({name: form.elements.name.value, registeredDate: form.elements.registeredDate.value, registrationStatus: status.value})})); });
            card.append(form); root.append(card);
        }
    }
    let timer;
    async function load() {
        clearTimeout(timer);
        try {
            if (companiesPage) return await renderCompanies();
            if (id) return await renderApplication();
            const applications = await request("/api/credit/applications");
            root.replaceChildren(node("h1", text("Мои заявки", "My applications")), link(text("Новая заявка", "New application"), "/app"));
            if (!applications.length) root.append(node("p", text("Заявок пока нет.", "No applications yet.")));
            for (const application of applications) {
                const card = node("article", undefined, "application-card dossier-card");
                card.append(node("h2", application.company || "—"), node("p", `${money(application.requestedAmount)} · ${application.termMonths} ${text("мес.", "months")} · ${label(application.state)}`),
                    link(text("Открыть заявку", "View application"), `/app/applications/${application.id}`));
                root.append(card);
            }
        } catch (failure) { error(failure); if (id) timer = setTimeout(load, 10000); }
    }
    document.getElementById("logout-form").addEventListener("submit", async event => {
        event.preventDefault(); try { await request(event.currentTarget.action, {method: "POST"}); location.assign("/login"); } catch (failure) { error(failure); }
    });
    window.addEventListener("pagehide", () => clearTimeout(timer));
    await load();
})();
