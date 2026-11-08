package work.stdpi.smolkey

import android.text.TextUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

@Composable
internal fun SmolkeyBrand(showWordmark: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Icon(
            painterResource(if (showWordmark) R.drawable.smolkey_logo else R.drawable.smolkey_mark),
            contentDescription = stringResource(R.string.app_wordmark),
            modifier = if (showWordmark) Modifier.size(136.dp, 34.dp) else Modifier.size(40.dp),
        )
    }
}

@Composable
internal fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_wordmark)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.credit_author))
                Text(stringResource(R.string.credit_engine))
                SampleCredit(
                    stringResource(R.string.piano), stringResource(R.string.credit_piano_source),
                    "https://github.com/sfzinstruments/SplendidGrandPiano",
                )
                SampleCredit(
                    stringResource(R.string.drums), stringResource(R.string.credit_percussion_source),
                    "https://github.com/sgossner/VCSL",
                )
                SampleCredit(
                    stringResource(R.string.tambourine), stringResource(R.string.credit_percussion_source),
                    "https://github.com/sgossner/VCSL/tree/c1ea7bcc3c7309650ab0da9d15c9cd1fbc4a4c7e/Idiophones/Struck%20Idiophones/Tambourine%201",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun SampleCredit(instrument: String, source: String, url: String) {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    Text(AnnotatedString.fromHtml(
        stringResource(R.string.sample_credit, TextUtils.htmlEncode(instrument), url, TextUtils.htmlEncode(source)),
        linkStyles = linkStyle,
    ))
}
