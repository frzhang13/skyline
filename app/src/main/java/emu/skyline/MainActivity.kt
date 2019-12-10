package emu.skyline

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.AdapterView.OnItemClickListener
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import emu.skyline.adapter.GameAdapter
import emu.skyline.adapter.GameItem
import emu.skyline.loader.BaseLoader
import emu.skyline.loader.NroLoader
import emu.skyline.loader.TitleEntry
import emu.skyline.loader.TitleFormat
import emu.skyline.utility.RandomAccessDocument
import kotlinx.android.synthetic.main.main_activity.*
import java.io.File
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), View.OnClickListener {
    private lateinit var sharedPreferences: SharedPreferences
    private var adapter = GameAdapter(this)
    private fun notifyUser(text: String) {
        Snackbar.make(findViewById(android.R.id.content), text, Snackbar.LENGTH_SHORT).show()
    }

    private fun findFile(ext: String, loader: BaseLoader, directory: DocumentFile, entries: MutableList<TitleEntry>): MutableList<TitleEntry> {
        var mEntries = entries
        for (file in directory.listFiles()) {
            if (file.isDirectory) {
                mEntries = findFile(ext, loader, file, mEntries)
            } else {
                try {
                    if (file.name != null) {
                        if (ext.equals(file.name?.substring((file.name!!.lastIndexOf(".")) + 1), ignoreCase = true)) {
                            val document = RandomAccessDocument(this, file)
                            if (loader.verifyFile(document))
                                mEntries.add(loader.getTitleEntry(document, file.uri))
                            document.close()
                        }
                    }
                } catch (e: StringIndexOutOfBoundsException) {
                    Log.w("findFile", e.message!!)
                }
            }
        }
        return mEntries
    }

    private fun refreshFiles(tryLoad: Boolean) {
        if (tryLoad) {
            try {
                adapter.load(File(applicationInfo.dataDir + "/roms.bin"))
                return
            } catch (e: Exception) {
                Log.w("refreshFiles", "Ran into exception while loading: " + e.message)
            }
        }
        adapter.clear()
        val entries: List<TitleEntry> = findFile("nro", NroLoader(this), DocumentFile.fromTreeUri(this, Uri.parse(sharedPreferences.getString("search_location", "")))!!, ArrayList())
        if (entries.isNotEmpty()) {
            adapter.addHeader(getString(R.string.nro))
            for (entry in entries)
                adapter.addItem(GameItem(entry))
        } else {
            adapter.addHeader(getString(R.string.no_rom))
        }
        try {
            adapter.save(File(applicationInfo.dataDir + "/roms.bin"))
        } catch (e: IOException) {
            Log.w("refreshFiles", "Ran into exception while saving: " + e.message)
        }
        sharedPreferences.edit().putBoolean("refresh_required", false).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        setSupportActionBar(toolbar)
        open_fab.setOnClickListener(this)
        log_fab.setOnClickListener(this)
        game_list.adapter = adapter
        game_list.onItemClickListener = OnItemClickListener { parent: AdapterView<*>, _: View?, position: Int, _: Long ->
            val item = parent.getItemAtPosition(position)
            if (item is GameItem) {
                val intent = Intent(this, GameActivity::class.java)
                intent.putExtra("romUri", item.uri)
                intent.putExtra("romType", item.meta.romType.ordinal)
                startActivity(intent)
            }
        }
        if (sharedPreferences.getString("search_location", "") == "") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
            startActivityForResult(intent, 1)
        } else
            refreshFiles(!sharedPreferences.getBoolean("refresh_required", false))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_main, menu)
        val mSearch = menu.findItem(R.id.action_search_main)
        val searchView = mSearch.actionView as SearchView
        searchView.isSubmitButtonEnabled = false
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                adapter.filter.filter(newText)
                return true
            }
        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.log_fab -> startActivity(Intent(this, LogActivity::class.java))
            R.id.open_fab -> {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(intent, 2)
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh -> {
                refreshFiles(false)
                notifyUser(getString(R.string.refreshed))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        if (sharedPreferences.getBoolean("refresh_required", false))
            refreshFiles(false)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                1 -> {
                    val uri = data!!.data!!
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    sharedPreferences.edit().putString("search_location", uri.toString()).apply()
                    refreshFiles(!sharedPreferences.getBoolean("refresh_required", false))
                }
                2 -> {
                    try {
                        val intent = Intent(this, GameActivity::class.java)
                        val uri = data!!.data!!
                        intent.putExtra("romUri", uri)
                        var uriStr = ""
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex: Int = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            cursor.moveToFirst()
                            uriStr = cursor.getString(nameIndex)
                        }
                        val type = TitleFormat.valueOf(uriStr.substring(uriStr.lastIndexOf(".") + 1).toUpperCase(Locale.ROOT))
                        intent.putExtra("romType", type)
                        startActivity(intent)
                    } catch (e: Exception) {
                        notifyUser(e.message!!)
                    }
                }
            }
        }
    }
}