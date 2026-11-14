package bayern.kickner.zoraxy.dyndns

import kotlinx.html.*
import kotlinx.html.stream.appendHTML

/** Message shown at the top of the page after an action. */
data class Notice(val ok: Boolean, val text: String)

/** A password just created for [client]. Rendered once, never stored. */
data class Secret(val client: String, val password: String)

/**
 * Form input to show again after a rejected action, so nobody has to type it twice.
 *
 * @property ruleId The rule whose form [ruleMode] and [ruleStatic] belong to.
 */
data class Drafts(
    val newClientName: String = "",
    val newClientRules: List<String> = emptyList(),
    val ruleId: String? = null,
    val ruleMode: String = "",
    val ruleStatic: String = "",
)

/**
 * Everything the status page shows.
 *
 * @property zoraxyRules Zoraxy's access rules by ID, a failure if Zoraxy could not be asked.
 * @property csrfToken Zoraxy's CSRF token from the `X-Zoraxy-Csrf` request header, sent back by `app.js`.
 */
data class PageView(
    val config: Config,
    val status: Status,
    val zoraxyRules: Result<Map<String, RuleSnapshot>>,
    val activity: List<ActivityEntry>,
    val csrfToken: String,
    val notice: Notice? = null,
    val secret: Secret? = null,
    val drafts: Drafts = Drafts(),
)

/** Zoraxy serves `icon.png` from the plugin folder here, its own placeholder if there is none. */
private const val ICON_URL = "/api/plugins/icon?plugin_id=bayern.kickner.zoraxy.dyndns"

private const val SETUP_GUIDE = "https://github.com/nexus421/zoraxy-dyndns/blob/master/docs/deployment.md"

private val modeHints = mapOf(
    Mode.ENFORCE to "The plugin keeps the whitelist on. Empty means everything is blocked.",
    Mode.WARN to "The plugin never touches the switch, it only warns while it is off.",
    Mode.OPEN_WHEN_EMPTY to "Off while empty: then everyone can access the service. Use with care.",
)

/**
 * Renders the whole status and settings page. Every value goes through kotlinx.html, which escapes it. No inline
 * script and no inline style: the page passes the CSP `script-src 'self'; style-src 'self'`. Paths are relative,
 * Zoraxy serves the page under `/plugin.ui/<id>/`, only the icon points to Zoraxy itself.
 */
fun renderPage(view: PageView): String = buildString {
    append("<!DOCTYPE html>\n")
    appendHTML(prettyPrint = false).html {
        lang = "en"
        attributes["data-theme"] = "dark"
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            meta(name = "csrf-token", content = view.csrfToken)
            title("DynDNS Whitelist")
            link(rel = "stylesheet", href = "app.css")
            script(src = "app.js") { defer = true }
        }
        body {
            main(classes = "container") {
                header(view)
                view.notice?.let { div("notice ${if (it.ok) "success" else "error"}") { id = "notice"; +it.text } }
                view.zoraxyRules.exceptionOrNull()?.let { div("notice error") { +"Cannot read Zoraxy's access rules: ${it.message}" } }
                view.secret?.let { secretBox(it, view.config.dyndnsHost) }
                clients(view)
                rules(view)
                activity(view.activity)
                footer {
                    +"zoraxy-dyndns ${BuildConfig.VERSION} · Update URL "
                    code { +"https://<client>:<password>@${view.config.dyndnsHost ?: "<zoraxy-host>"}${Introspect.CAPTURE_PATH}?myip=<ipaddr>" }
                    +" · "
                    a(SETUP_GUIDE, target = "_blank") { +"Setup guide" }
                }
            }
        }
    }
}

private fun FlowContent.header(view: PageView) {
    div("head") {
        img(src = ICON_URL, classes = "logo") { alt = "" }
        div {
            h1 { +"DynDNS Whitelist" }
            div("sub muted small") { +"Keeps the IPs your routers report in Zoraxy's whitelists" }
        }
        div("spacer")
        span("pill accent") { +"v${BuildConfig.VERSION}" }
    }
    form(action = "settings", method = FormMethod.post, classes = "card host-form") {
        label { htmlFor = "dyndns-host"; +"DynDNS host" }
        textInput(name = "dyndns_host") {
            id = "dyndns-host"
            value = view.config.dyndnsHost.orEmpty()
            placeholder = "dyndns.example.com"
        }
        button(classes = "btn sm", type = ButtonType.submit) { +"Save" }
        span("hint") { +"The Zoraxy host with the tag dyndns. Used for the router settings shown after a new password." }
    }
}

private fun FlowContent.secretBox(secret: Secret, dyndnsHost: String?) {
    val host = dyndnsHost ?: "<zoraxy-host>"
    section("card attention secret") {
        div("card-header") {
            div {
                h2 { +"Password for ${secret.client}" }
                div("muted small") { +"Shown only now. The plugin stores just its SHA-256, a lost password gets replaced, not recovered." }
            }
            button(classes = "btn ghost sm", type = ButtonType.button) { attributes["data-dismiss"] = ""; +"Done" }
        }
        div("muted small") { +"FRITZ!Box: Internet, Freigaben, DynDNS, Benutzerdefiniert" }
        copyLine("Update-URL", "secret-url", "https://<username>:<pass>@$host/nic/update?hostname=<domain>&myip=<ipaddr>,<ip6lanprefix>")
        // The FRITZ!Box resolves this field to check the update, a port does not belong there
        copyLine("Domainname", "secret-domain", host.substringBefore(':'))
        copyLine("Benutzername", "secret-user", secret.client)
        copyLine("Kennwort", "secret-password", secret.password)
        div("hint") { +"Other dyndns2 clients: server $host, path /nic/update, user ${secret.client} and this password, HTTPS." }
    }
}

private fun FlowContent.copyLine(label: String, id: String, value: String) {
    div {
        div("muted small copy-label") { +label }
        div("copy-line") {
            code { this.id = id; +value }
            button(classes = "btn sm", type = ButtonType.button) { attributes["data-copy"] = id; +"Copy" }
        }
    }
}

private fun FlowContent.clients(view: PageView) {
    val names = view.zoraxyRules.getOrNull().orEmpty().mapValues { it.value.name }
    val zoraxyUp = view.zoraxyRules.isSuccess
    // Without Zoraxy only rules the config has already can be picked, the action accepts nothing else then
    val choices = if (zoraxyUp) names.keys.toList() else view.config.ruleIds().toList()
    val drafts = view.drafts
    val addOpen = drafts.newClientName.isNotEmpty() || drafts.newClientRules.isNotEmpty()
    section("card") {
        div("card-header") {
            div {
                h2 { +"Clients" }
                div("muted small") { +"Routers and DynDNS clients that may report their address" }
            }
            button(classes = "btn primary", type = ButtonType.button) { attributes["data-toggle"] = "add-client"; +"Add client" }
        }
        form(action = "client/add", method = FormMethod.post, classes = "card inset") {
            id = "add-client"
            if (addOpen.not()) hidden = true
            h2 { +"New client" }
            div("form") {
                label("field") {
                    span { +"Name" }
                    textInput(name = "name") { value = drafts.newClientName; placeholder = "fritzbox-home" }
                    span("hint") { +"The user name in the router. Letters, digits, dot, dash, underscore." }
                }
                div("field") {
                    span { +"Access rules" }
                    ruleChecks(choices, drafts.newClientRules, names, zoraxyUp)
                    span("hint") { +"The whitelists this client's address goes into. Pick at least one." }
                }
                div { button(classes = "btn primary", type = ButtonType.submit) { +"Create and show password" } }
            }
        }
        div("clients") {
            if (view.config.clients.isEmpty()) div("muted") { +"No clients yet. Add one, then enter its name and password in your router." }
            view.config.clients.forEachIndexed { i, c -> client(i, c, view, names, zoraxyUp) }
        }
    }
}

private fun FlowContent.client(index: Int, c: Client, view: PageView, names: Map<String, String>, zoraxyUp: Boolean) {
    val state = view.status.clients[c.name]
    val result = view.status.results[c.name]
    div("client") {
        div {
            div("name") {
                +c.name
                resultPill(result)
            }
            div("pills") { c.rules.forEach { span("pill") { +(names[it] ?: it) } } }
        }
        div {
            div("label") { +"Last addresses" }
            div("mono break") {
                if (state == null || state.addresses.isEmpty()) span("muted") { +"none yet" }
                state?.addresses?.forEach { div { +it.display } }
            }
        }
        div {
            div("label") { +"Last update" }
            div("small") { +(state?.let { formatTimestamp(it.updatedAt) } ?: "never") }
            if (result != null) div("small muted") { +"Last answer ${formatTimestamp(result.at)}" }
        }
        div("row-actions") {
            button(classes = "btn sm", type = ButtonType.button) { attributes["data-toggle"] = "rules-$index"; +"Rules" }
            nameForm("client/password", c.name, "New password", "Really replace?", "btn sm")
            nameForm("client/delete", c.name, "Delete", "Really delete?", "btn sm danger")
        }
        form(action = "client/rules", method = FormMethod.post, classes = "edit form") {
            id = "rules-$index"
            hidden = true
            hiddenInput(name = "name") { value = c.name }
            ruleChecks((names.keys + c.rules).distinct(), c.rules, names, zoraxyUp)
            div { button(classes = "btn primary sm", type = ButtonType.submit) { +"Save rules" } }
        }
    }
}

private fun FlowContent.resultPill(result: ClientResult?) {
    if (result == null) return span("pill dot") { +"never reported" }
    val word = result.text.substringBefore(' ')
    val kind = when (word) {
        "good", "nochg" -> "ok"
        "911" -> "error"
        else -> "warn"
    }
    span("pill dot $kind") { title = result.text; +word }
}

private fun FlowContent.nameForm(action: String, name: String, label: String, confirm: String, classes: String) {
    form(action = action, method = FormMethod.post) {
        hiddenInput(name = "name") { value = name }
        button(classes = classes, type = ButtonType.submit) { attributes["data-confirm"] = confirm; +label }
    }
}

/** Rule checkboxes by name. An ID Zoraxy does not list is marked as such, unless Zoraxy could not be asked. */
private fun FlowContent.ruleChecks(ids: List<String>, checked: List<String>, names: Map<String, String>, zoraxyUp: Boolean) {
    div("checks") {
        ids.forEach { id ->
            label {
                checkBoxInput(name = "rule") { value = id; this.checked = id in checked }
                +" ${names[id] ?: if (zoraxyUp) "$id (not in Zoraxy)" else id}"
            }
        }
    }
}

private fun FlowContent.rules(view: PageView) {
    val zoraxy = view.zoraxyRules.getOrNull().orEmpty()
    val managed = view.config.ruleIds()
    section("card") {
        div("card-header") {
            div {
                h2 { +"Access rules" }
                div("muted small") {
                    +"Every rule the plugin manages. It only touches entries marked "
                    code { +"dyndns:" }
                }
            }
        }
        div("rules") { managed.forEach { rule(it, view, zoraxy[it]) } }
        val free = zoraxy.values.filter { it.id !in managed }
        form(action = "rule/add", method = FormMethod.post, classes = "add-rule") {
            val reason = when {
                view.zoraxyRules.isFailure -> "Zoraxy is not reachable"
                free.isEmpty() -> "All rules are managed"
                else -> null
            }
            select {
                name = "id"
                if (reason != null) option { disabled = true; +reason }
                free.forEach { option { value = it.id; +it.name } }
            }
            button(classes = "btn", type = ButtonType.submit) {
                if (reason != null) disabled = true
                +"Manage this rule too"
            }
        }
        div("hint add-rule-hint") { +"For a rule with fixed entries only, for example an office or VPN range without any router." }
    }
}

private fun FlowContent.rule(id: String, view: PageView, snapshot: RuleSnapshot?) {
    val config = view.config.ruleConfig(id)
    val status = view.status.rules[id]
    val owners = view.config.clients.filter { id in it.rules }.map { it.name }
    val draft = view.drafts.takeIf { it.ruleId == id }
    val mode = draft?.ruleMode?.let { runCatching { Mode.parse(it) }.getOrNull() } ?: config.mode
    form(action = "rule/save", method = FormMethod.post, classes = "rule") {
        hiddenInput(name = "id") { value = id }
        div("top") {
            h2 { +(snapshot?.name ?: id) }
            span("muted small mono") { +id }
        }
        div("pills") {
            when (status?.enabled) {
                true -> span("pill dot ok") { +"whitelist on" }
                false -> span("pill dot error") { +"whitelist OFF" }
                null -> span("pill dot") { +"unknown" }
            }
            status?.let { span("pill") { +"${it.entries} entr${if (it.entries == 1) "y" else "ies"}" } }
            span("pill") { +(if (owners.isEmpty()) "fixed entries only" else "used by ${owners.joinToString()}") }
        }
        status?.problems?.forEach { div("notice warn small") { +it } }
        label("field") {
            span { +"Mode" }
            select {
                name = "mode"
                Mode.entries.forEach { m -> option { value = m.json; selected = m == mode; +m.json } }
            }
            span("mode-hint" + if (mode == Mode.OPEN_WHEN_EMPTY) " danger" else "") { +modeHints.getValue(mode) }
        }
        label("field") {
            span { +"Fixed entries" }
            textArea {
                name = "static"
                placeholder = "203.0.113.10\n10.8.0.0/24"
                +(draft?.ruleStatic ?: config.static.joinToString("\n"))
            }
            span("hint") { +"One per line. Private ranges allowed, IPv4 /8 to /32, IPv6 /16 to /128." }
        }
        div("row-actions") {
            button(classes = "btn primary sm", type = ButtonType.submit) { +"Save" }
            if (owners.isEmpty()) button(classes = "btn sm danger", type = ButtonType.submit) {
                attributes["formaction"] = "rule/release"
                attributes["data-confirm"] = "Really stop?"
                +"Stop managing"
            }
        }
    }
}

private fun FlowContent.activity(entries: List<ActivityEntry>) {
    details("card") {
        id = "activity"
        summary { h2 { +"Recent activity (${entries.size})" } }
        if (entries.isEmpty()) div("muted small") { +"Nothing logged since the plugin started." }
        ul("activity") {
            entries.forEach { e ->
                li {
                    time { +formatTimestamp(e.at) }
                    span("mono") { +e.line }
                }
            }
        }
    }
}
