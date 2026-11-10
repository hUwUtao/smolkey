package work.stdpi.ensemble

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.Collections

sealed interface BankSource {
    data class Asset(val path: String) : BankSource
    data class LocalFile(val file: File) : BankSource
    data class ContentUri(val uri: Uri) : BankSource {
        init { require(uri.scheme == ContentResolver.SCHEME_CONTENT) }
    }
}

class Bank internal constructor(
    val source: BankSource,
    val clipCount: Int,
    programs: Collection<Int>,
) {
    val programs: Set<Int> = Collections.unmodifiableSet(programs.toSet())
}

sealed interface BankResult {
    data class Loaded(val bank: Bank) : BankResult
    data class MissingPrograms(val ids: Set<Int>) : BankResult
    data class AlreadyLoaded(val bank: Bank) : BankResult
    data object Unreadable : BankResult
    data object Closed : BankResult
}

/** Owns the one streamed bank for an engine. Load another bank in a separate engine. */
class BankManager internal constructor(
    private val context: Context,
    private val handle: () -> Long,
) {
    @Volatile private var installed: Bank? = null

    val current: Bank?
        get() = installed.takeIf { handle() != 0L }

    /** Reads metadata on the calling worker without installing the bank. */
    fun inspect(source: BankSource): Bank? {
        val metadata = withSource(source, EnsembleBindings::inspectBank) ?: return null
        return metadata.toBank(source)
    }

    /** Reads the index on a worker thread. Missing programs leave the engine unloaded. */
    @Synchronized
    fun load(source: BankSource, requiredPrograms: Set<Int> = emptySet()): BankResult {
        val owner = handle()
        if (owner == 0L) return BankResult.Closed
        require(requiredPrograms.all { it in 0..65535 })
        installed?.let { bank ->
            if (bank.source != source) return BankResult.AlreadyLoaded(bank)
            val missing = requiredPrograms - bank.programs
            return if (missing.isEmpty()) BankResult.Loaded(bank) else BankResult.MissingPrograms(missing)
        }

        val required = requiredPrograms.toIntArray()
        val metadata = withSource(source) { fd, offset, length ->
            EnsembleBindings.loadBank(owner, fd, offset, length, required)
        }
        if (handle() != owner) return BankResult.Closed
        if (metadata == null || metadata.isEmpty()) return BankResult.Unreadable
        if (metadata[0] == -2) return BankResult.MissingPrograms(metadata.drop(1).toSet())
        val bank = metadata.toBank(source) ?: return BankResult.Unreadable
        installed = bank
        return BankResult.Loaded(bank)
    }

    private fun IntArray.toBank(source: BankSource): Bank? =
        if (size > 1 && this[0] > 0) Bank(source, this[0], drop(1)) else null

    private fun withSource(source: BankSource, read: (Int, Long, Long) -> IntArray?): IntArray? {
        val descriptor = try {
            when (source) {
                is BankSource.Asset -> context.assets.openFd(source.path)
                is BankSource.LocalFile -> AssetFileDescriptor(
                    ParcelFileDescriptor.open(source.file, ParcelFileDescriptor.MODE_READ_ONLY),
                    0, AssetFileDescriptor.UNKNOWN_LENGTH,
                )
                is BankSource.ContentUri -> context.contentResolver.openAssetFileDescriptor(source.uri, "r")
            }
        } catch (_: IOException) {
            return null
        } catch (_: SecurityException) {
            return null
        } ?: return null

        try {
            return read(descriptor.parcelFileDescriptor.fd, descriptor.startOffset, descriptor.declaredLength)
        } finally {
            try {
                descriptor.close()
            } catch (_: IOException) {
                // Native reads are already complete or own a duplicate.
            }
        }
    }
}
