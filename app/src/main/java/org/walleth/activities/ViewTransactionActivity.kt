package org.walleth.activities

import android.arch.lifecycle.Observer
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.github.salomonbrys.kodein.LazyKodein
import com.github.salomonbrys.kodein.android.appKodein
import com.github.salomonbrys.kodein.instance
import kotlinx.android.synthetic.main.activity_view_transaction.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import net.glxn.qrgen.android.QRCode
import org.kethereum.functions.encodeRLP
import org.ligi.kaxt.setVisibility
import org.ligi.kaxt.startActivityFromURL
import org.walleth.R
import org.walleth.data.AppDatabase
import org.walleth.data.addressbook.resolveNameAsync
import org.walleth.data.keystore.WallethKeyStore
import org.walleth.data.networks.CurrentAddressProvider
import org.walleth.data.networks.NetworkDefinitionProvider
import org.walleth.data.tokens.getEthTokenForChain
import org.walleth.data.transactions.TransactionEntity
import org.walleth.khex.toHexString


class ViewTransactionActivity : AppCompatActivity() {

    companion object {
        private val HASH_KEY = "TXHASH"
        fun Context.getTransactionActivityIntentForHash(hex: String)
                = Intent(this, ViewTransactionActivity::class.java).apply {
            putExtra(HASH_KEY, hex)
        }
    }

    val keyStore: WallethKeyStore by LazyKodein(appKodein).instance()
    val appDatabase: AppDatabase by LazyKodein(appKodein).instance()
    val currentAddressProvider: CurrentAddressProvider by LazyKodein(appKodein).instance()
    val networkDefinitionProvider: NetworkDefinitionProvider by LazyKodein(appKodein).instance()
    var txEntity: TransactionEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_view_transaction)
    }

    override fun onResume() {
        super.onResume()

        appDatabase.transactions.getByHashLive(intent.getStringExtra(HASH_KEY)).observe(this, Observer<TransactionEntity> {
            if (it != null) {
                txEntity = it
                invalidateOptionsMenu()
                val transaction = it.transaction

                supportActionBar?.subtitle = getString(R.string.transaction_subtitle)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)

                nonce.text = transaction.nonce.toString()
                event_log_textview.text = it.transactionState.eventLog

                fab.setVisibility(it.transactionState.needsSigningConfirmation)
                fab.setOnClickListener { _ ->
                    it.transactionState.needsSigningConfirmation = false
                    appDatabase.transactions.upsert(it)
                    finish()
                }

                fee_value_view.setValue(it.transaction.gasLimit * it.transaction.gasPrice, getEthTokenForChain(networkDefinitionProvider.getCurrent()))

                val relevant_address = if (it.transaction.from == currentAddressProvider.getCurrent()) {
                    from_to_title.setText(R.string.transaction_to_label)
                    it.transaction.to
                } else {
                    from_to_title.setText(R.string.transaction_from_label)
                    it.transaction.from
                }

                relevant_address?.let { ensured_relevant_address ->
                    appDatabase.addressBook.resolveNameAsync(ensured_relevant_address) { name ->
                        from_to.text = name

                        add_address.setVisibility(name == ensured_relevant_address.hex)
                    }

                    add_address.setOnClickListener {
                        startCreateAccountActivity(ensured_relevant_address.hex)
                    }

                    copy_address.setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(getString(R.string.ethereum_address), ensured_relevant_address.hex)
                        clipboard.primaryClip = clip
                        Snackbar.make(fab, R.string.snackbar_after_address_copy, Snackbar.LENGTH_LONG).show()
                    }
                }


                if (it.transactionState.isPending && (!it.transactionState.relayedEtherscan && !it.transactionState.relayedLightClient)) {
                    rlp_header.setText(if (it.signatureData != null) {
                        R.string.signed_rlp_header_text
                    } else {
                        R.string.unsigned_rlp_header_text
                    })
                    rlp_image.setImageBitmap(QRCode.from(it.transaction.encodeRLP().toHexString()).bitmap())
                } else {
                    rlp_image.visibility = View.GONE
                    rlp_header.visibility = View.GONE
                }

                value_view.setValue(it.transaction.value, getEthTokenForChain(networkDefinitionProvider.getCurrent()))

                details.text = it.transaction.txHash
            }
        })

    }

    override fun onCreateOptionsMenu(menu: Menu?)
            = super.onCreateOptionsMenu(menu.apply { menuInflater.inflate(R.menu.menu_transaction, menu) })

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        menu?.findItem(R.id.menu_delete)?.isVisible = txEntity?.transactionState?.isPending ?: false
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_delete -> {
            txEntity?.hash?.let {
                async(UI) {
                    async(CommonPool) {
                        appDatabase.transactions.deleteByHash(it)
                    }.await()
                    finish()
                }
            }
            true
        }

        R.id.menu_etherscan -> {
            txEntity?.let {
                val url = networkDefinitionProvider.value!!.getBlockExplorer().getURLforTransaction(it.transaction.txHash!!)
                startActivityFromURL(url)
            }
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
