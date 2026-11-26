package org.fossify.filemanager.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.RecyclerView
import ch.rosenweg4303.cloud.Eintrag
import ch.rosenweg4303.cloud.Konto
import ch.rosenweg4303.cloud.KontoSpeicher
import ch.rosenweg4303.cloud.LoginFlow
import ch.rosenweg4303.cloud.NextcloudFehler
import ch.rosenweg4303.cloud.WebDav
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.filemanager.R
import org.fossify.filemanager.databinding.ActivityNextcloudBinding
import org.fossify.filemanager.databinding.ItemFileDirListBinding
import org.fossify.filemanager.extensions.config
import org.fossify.filemanager.helpers.alsOrtChip
import org.fossify.filemanager.nextcloud.Nextcloud
import org.fossify.filemanager.nextcloud.Nextcloud.APP_NAME
import org.fossify.filemanager.nextcloud.Rueckschreiben

/**
 * Rosenweg Files: die Dateien der Person in der Rosenweg-Nextcloud.
 *
 * Grundregel: Die Dateien bleiben in der Nextcloud. Zum Oeffnen wird nur in den
 * Cache geladen; der Cache wird beim Abmelden geleert und alte Kopien beim
 * naechsten Start entfernt. Braucht keine andere Rosenweg-App.
 *
 * Geoeffnet wird ueber den NextcloudProvider: Die App, die die Datei anzeigt,
 * darf sie (wenn die Nextcloud es erlaubt) auch speichern — die Aenderung geht
 * zurueck in die Nextcloud.
 */
class NextcloudActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityNextcloudBinding::inflate)
    private val speicher by lazy { KontoSpeicher(this) }
    private var dav: WebDav? = null
    private var pfad = ""
    private var eintraege = listOf<Eintrag>()
    private var login: LoginFlow? = null
    private var ersterResume = true
    /** Beim ersten Laden in "Persönlich" springen (AD-Home), falls es den Ordner gibt. */
    private var startOrdnerOffen = true

    private val dateienWaehlen = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) hochladen(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        pfad = savedInstanceState?.getString(PFAD) ?: ""
        if (savedInstanceState != null) startOrdnerOffen = false
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ncList))
            setupMaterialScrollListener(ncList, ncAppbar)
            ncList.adapter = Liste()
            ncRefresh.setOnRefreshListener { laden() }
            ncLogin.paintFlags = ncLogin.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            ncLogin.setOnClickListener { anmelden() }
            ncOrtIntern.setOnClickListener { finish() }
            // Stefan 25.11.2026: mit Rosenweg Geraet nur die Nextcloud; Downloads laden automatisch hoch
            ncOrte.beVisibleIf(!ch.rosenweg4303.cloud.RosenwegAnmeldung.geraetDa(this@NextcloudActivity))
            ncToolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.nc_hochladen -> dateienWaehlen.launch(arrayOf("*/*"))
                    R.id.nc_neuer_ordner -> nameAbfragen(R.string.nc_neuer_ordner, "") { neuerOrdner(it) }
                    R.id.nc_aktualisieren -> laden()
                    R.id.nc_abmelden -> abmeldenFragen()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
        }
        ensureBackgroundThread { Nextcloud.cacheAufraeumen(this, maxAlterMs = 60 * 60 * 1000L) }
        Rueckschreiben.anstossen(this)
        dav = speicher.laden()?.let { WebDav(it, APP_NAME) }
        anzeigen()
        if (dav != null) laden()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ncAppbar, NavigationIcon.Arrow)
        binding.ncToolbar.setNavigationOnClickListener { if (!hoch()) finish() }
        binding.ncPlaceholder.setTextColor(getProperTextColor())
        binding.ncLogin.setTextColor(getProperPrimaryColor())
        binding.ncOrtIntern.alsOrtChip(aktiv = false, symbol = org.fossify.commons.R.drawable.ic_phone_vector)
        binding.ncOrtNextcloud.alsOrtChip(aktiv = true)
        // zurueck aus einer App, die eine Datei bearbeitet hat: Liste auffrischen
        if (!ersterResume && dav != null && login == null) laden()
        // zurueck vom gemeinsamen Anmeldeschirm: angemeldet -> Nextcloud-Zugang holen
        if (wartetAufSchirm) {
            wartetAufSchirm = false
            if (dav == null && ch.rosenweg4303.cloud.RosenwegAnmeldung.angemeldet(this)) anmelden()
        }
        ersterResume = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PFAD, pfad)
    }

    override fun onDestroy() {
        login?.abgebrochen = true
        super.onDestroy()
    }

    override fun onBackPressedCompat() = hoch()

    private fun hoch(): Boolean {
        if (dav == null || pfad.isEmpty()) return false
        pfad = pfad.substringBeforeLast('/', "")
        laden()
        return true
    }

    private fun anzeigen() {
        val angemeldet = dav != null
        binding.apply {
            ncToolbar.menu.findItem(R.id.nc_hochladen)?.isVisible = angemeldet
            ncToolbar.menu.findItem(R.id.nc_neuer_ordner)?.isVisible = angemeldet
            ncToolbar.menu.findItem(R.id.nc_aktualisieren)?.isVisible = angemeldet
            ncToolbar.menu.findItem(R.id.nc_abmelden)?.isVisible = angemeldet
            ncToolbar.title = if (pfad.isEmpty()) getString(R.string.nc_titel) else pfad.substringAfterLast('/')
            ncRefresh.isEnabled = angemeldet
            ncLogin.beVisibleIf(!angemeldet && login == null)
            ncPlaceholder.beVisibleIf(!angemeldet || eintraege.isEmpty())
            ncPlaceholder.text = when {
                login != null -> getString(R.string.nc_warte_auf_anmeldung)
                !angemeldet -> getString(R.string.nc_nicht_angemeldet)
                else -> getString(R.string.nc_leer)
            }
            ncList.adapter?.notifyDataSetChanged()
        }
    }

    // ---- Anmelden / Abmelden ----

    /** Gemeinsamer Anmeldeschirm (Karte oder Rosenweg-Konto) ist offen. */
    private var wartetAufSchirm = false

    /**
     * Eigenes Handy (Stefan 26.11.2026, ab v36): erst in der App anmelden - gemeinsamer
     * Anmeldeschirm aus commons-rosenweg (Rosenweg-Karte oder Rosenweg-Konto, wie der Sperrbildschirm
     * der geteilten Telefone) -, dann holt NextcloudZugang (commons) das App-Passwort von der Anlage.
     * Geht das nicht, bleibt die Anmeldung direkt bei der Nextcloud im Browser.
     */
    private fun anmelden() {
        val anmeldung = ch.rosenweg4303.cloud.RosenwegAnmeldung
        if (anmeldung.geraetDa(this)) {
            loginFlow()
            return
        }
        if (!anmeldung.angemeldet(this)) {
            wartetAufSchirm = true
            // ohne Anmeldung ist die App nicht nutzbar: "Zurueck" auf dem Schirm schliesst sie (Standard)
            startActivity(Intent(this, ch.rosenweg4303.cloud.RosenwegAnmeldeSchirm::class.java))
            return
        }
        ensureBackgroundThread {
            try {
                val konto = ch.rosenweg4303.cloud.NextcloudZugang.holen(this, APP_NAME)
                speicher.speichern(konto)
                Nextcloud.anmeldungGeaendert(this)
                Rueckschreiben.anstossen(this)
                runOnUiThread {
                    dav = WebDav(konto, APP_NAME)
                    pfad = ""
                    startOrdnerOffen = true
                    laden()
                }
            } catch (e: Exception) {
                runOnUiThread { loginFlow() }   // z.B. Anlage ohne diesen Weg: direkt bei der Nextcloud
            }
        }
    }

    /** Anmeldung direkt bei der Nextcloud im Browser (Login-Flow v2). */
    private fun loginFlow() {
        val flow = LoginFlow(APP_NAME)
        login = flow
        anzeigen()
        ensureBackgroundThread {
            try {
                flow.starten()
                runOnUiThread { browserOeffnen(flow.anmeldeAdresse) }
                val konto = flow.warten()
                speicher.speichern(konto)
                Nextcloud.anmeldungGeaendert(this)
                Rueckschreiben.anstossen(this)
                runOnUiThread {
                    login = null
                    dav = WebDav(konto, APP_NAME)
                    pfad = ""
                    startOrdnerOffen = true
                    // zurueck in die App holen, falls der Browser noch vorne ist
                    startActivity(Intent(this, NextcloudActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    laden()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    login = null
                    anzeigen()
                    if (!flow.abgebrochen) fehler(e)
                }
            }
        }
    }

    private fun browserOeffnen(adresse: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(adresse)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.nc_keine_app)
        }
    }

    private fun abmeldenFragen() {
        val offen = Rueckschreiben.anzahl(this)
        if (offen > 0) {
            // Grundregel: nichts verlieren, was noch nicht in der Nextcloud ist
            Rueckschreiben.anstossen(this)
            toast(getString(R.string.nc_rueck_offen, offen))
            return
        }
        ConfirmationDialog(this, getString(R.string.nc_abmelden_frage), positive = R.string.yes, negative = R.string.no) {
            val alt = dav
            dav = null
            eintraege = emptyList()
            pfad = ""
            speicher.loeschen()
            Nextcloud.anmeldungGeaendert(this)
            anzeigen()
            ensureBackgroundThread {
                alt?.appPasswortWiderrufen()
                Nextcloud.cacheAufraeumen(this, maxAlterMs = 0)
            }
        }
    }

    private fun anmeldungVerloren() {
        dav = null
        eintraege = emptyList()
        speicher.loeschen()
        Nextcloud.anmeldungGeaendert(this)
        anzeigen()
    }

    // ---- Ordner ----

    private fun laden() {
        val d = dav ?: return
        val ziel = pfad
        binding.ncRefresh.isRefreshing = true
        ensureBackgroundThread {
            try {
                val liste = d.liste(ziel)
                if (startOrdnerOffen && ziel.isEmpty()) {
                    startOrdnerOffen = false
                    if (liste.any { it.istOrdner && it.pfad == PERSOENLICH }) {
                        runOnUiThread {
                            if (pfad.isEmpty()) {
                                pfad = PERSOENLICH
                                laden()
                            }
                        }
                        return@ensureBackgroundThread
                    }
                }
                runOnUiThread {
                    // versteckte Dateien (.cache, .config ... der Linux-Stationen) wie lokal nur auf Wunsch
                    if (ziel == pfad) eintraege = if (config.shouldShowHidden()) liste else liste.filterNot { it.name.startsWith(".") }
                    binding.ncRefresh.isRefreshing = false
                    anzeigen()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.ncRefresh.isRefreshing = false
                    fehler(e)
                    anzeigen()
                }
            }
        }
    }

    private fun arbeit(meldung: String? = null, aufgabe: (WebDav) -> Unit) {
        val d = dav ?: return
        binding.ncRefresh.isRefreshing = true
        ensureBackgroundThread {
            try {
                aufgabe(d)
                runOnUiThread {
                    meldung?.let { toast(it) }
                    laden()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.ncRefresh.isRefreshing = false
                    fehler(e)
                }
            }
        }
    }

    private fun kind(name: String) = if (pfad.isEmpty()) name else "$pfad/$name"

    private fun neuerOrdner(name: String) = arbeit { it.ordnerAnlegen(kind(name)) }

    private fun umbenennen(e: Eintrag, neu: String) {
        if (neu == e.name) return
        val ziel = e.pfad.substringBeforeLast('/', "").let { if (it.isEmpty()) neu else "$it/$neu" }
        arbeit { it.verschieben(e.pfad, ziel) }
    }

    private fun loeschen(e: Eintrag) {
        ConfirmationDialog(this, getString(R.string.nc_loeschen_frage, e.name), positive = R.string.yes, negative = R.string.no) {
            arbeit { it.loeschen(e.pfad) }
        }
    }

    private fun hochladen(uris: List<Uri>) {
        arbeit(getString(R.string.nc_hochgeladen, uris.size)) { d ->
            for (uri in uris) {
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Datei"
                var laenge = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) laenge = c.getLong(1)
                    }
                }
                d.hochladen(kind(name), { contentResolver.openInputStream(uri)!! }, laenge, contentResolver.getType(uri))
            }
        }
    }

    // ---- Dateien oeffnen ----

    private fun oeffnen(e: Eintrag) {
        if (e.istOrdner) {
            pfad = e.pfad
            eintraege = emptyList()
            anzeigen()
            laden()
            return
        }
        val d = dav ?: return
        if (istOffice(e) && e.fileId > 0) {
            officeOeffnen(e, d)
            return
        }
        laden(e, d)
    }

    /**
     * Office-Dateien: bearbeitet wird in Euro-Office (Browser, Nextcloud). Eine
     * App auf dem Telefon kann sie hoechstens anzeigen — gibt es keine, gleich
     * in den Browser.
     */
    private fun officeOeffnen(e: Eintrag, d: WebDav) {
        val app = Intent(Intent.ACTION_VIEW).setDataAndType(Nextcloud.dokumentUri(this, e.pfad), e.mime)
        if (packageManager.queryIntentActivities(app, 0).none { it.activityInfo.packageName != packageName }) {
            browserOeffnen(d.officeAdresse(e))
            return
        }
        val punkte = arrayOf(getString(R.string.nc_in_euro_office), getString(R.string.nc_mit_app))
        getAlertDialogBuilder().setItems(punkte) { _, i ->
            if (i == 0) browserOeffnen(d.officeAdresse(e)) else laden(e, d)
        }.setTitle(e.name).show()
    }

    private fun istOffice(e: Eintrag) =
        e.name.substringAfterLast('.', "").lowercase() in OFFICE_ENDUNGEN

    private fun laden(e: Eintrag, d: WebDav) {
        // vorab in den Cache laden, damit die andere App nicht auf das Netz wartet
        val ziel = Nextcloud.cacheDatei(this, e)
        if (ziel.exists()) {
            zeigen(e)
            return
        }
        toast(getString(R.string.nc_wird_geladen, e.name))
        ensureBackgroundThread {
            try {
                d.herunterladen(e.pfad, ziel)
                runOnUiThread { zeigen(e) }
            } catch (ex: Exception) {
                runOnUiThread { fehler(ex) }
            }
        }
    }

    private fun zeigen(e: Eintrag) {
        val uri = Nextcloud.dokumentUri(this, e.pfad)
        var rechte = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (e.darfSchreiben) rechte = rechte or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, e.mime).addFlags(rechte)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.nc_keine_app)
        }
    }

    // ---- Hilfen ----

    private fun fehler(e: Exception) {
        if (e is NextcloudFehler && e.anmeldungNoetig) {
            anmeldungVerloren()
        }
        toast(getString(R.string.nc_fehler, e.message ?: e.javaClass.simpleName))
    }

    private fun nameAbfragen(titel: Int, vorgabe: String, fertig: (String) -> Unit) {
        val feld = EditText(this).apply {
            setText(vorgabe)
            setSingleLine()
            hint = getString(R.string.nc_name)
            setSelection(vorgabe.substringBeforeLast('.', vorgabe).length)
        }
        val rahmen = FrameLayout(this).apply {
            val p = resources.getDimensionPixelSize(org.fossify.commons.R.dimen.activity_margin)
            setPadding(p, p, p, 0)
            addView(feld)
        }
        getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(rahmen, this, titel) { dialog ->
                    showKeyboard(feld)
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        val name = feld.text.toString().trim()
                        if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return@setOnClickListener
                        dialog.dismiss()
                        fertig(name)
                    }
                }
            }
    }

    private fun aktionen(e: Eintrag) {
        val punkte = mutableListOf<Pair<String, () -> Unit>>()
        if (e.darfUmbenennen) punkte += getString(R.string.nc_umbenennen) to { nameAbfragen(R.string.nc_umbenennen, e.name) { umbenennen(e, it) } }
        if (e.darfLoeschen) punkte += getString(R.string.nc_loeschen) to { loeschen(e) }
        val d = dav
        if (!e.istOrdner && e.fileId > 0 && d != null) punkte += getString(R.string.nc_im_browser) to { browserOeffnen(d.webAdresse(e)) }
        if (punkte.isEmpty()) return
        getAlertDialogBuilder().setItems(punkte.map { it.first }.toTypedArray()) { _, i -> punkte[i].second() }
            .setTitle(e.name).show()
    }

    private inner class Liste : RecyclerView.Adapter<Liste.Zeile>() {
        inner class Zeile(val b: ItemFileDirListBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Zeile(ItemFileDirListBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = if (dav == null) 0 else eintraege.size

        override fun onBindViewHolder(h: Zeile, position: Int) {
            val e = eintraege[position]
            val farbe = getProperTextColor()
            h.b.apply {
                itemName.text = e.name
                itemName.setTextColor(farbe)
                itemDetails.text = e.groesse.formatSize()
                itemDetails.setTextColor(farbe)
                itemDate.text = if (e.geaendert > 0) e.geaendert.formatDate(this@NextcloudActivity) else ""
                itemDate.setTextColor(farbe)
                itemIcon.setImageResource(if (e.istOrdner) org.fossify.commons.R.drawable.ic_folder_vector else org.fossify.commons.R.drawable.ic_file_generic)
                if (e.istOrdner) itemIcon.applyColorFilter(getProperPrimaryColor()) else itemIcon.clearColorFilter()
                root.setOnClickListener { oeffnen(e) }
                root.setOnLongClickListener { aktionen(e); true }
            }
        }
    }

    private companion object {
        const val PFAD = "pfad"
        const val PERSOENLICH = "Persönlich"
        val OFFICE_ENDUNGEN = setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp")
    }
}
