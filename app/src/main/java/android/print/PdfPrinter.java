package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

/**
 * Drives a {@link PrintDocumentAdapter} straight into a file, without the system print
 * dialog. The result callbacks have no public constructor in the SDK, which is why this
 * class sits in the android.print package. Callers must be ready for this to throw on
 * devices that block it and fall back to {@link PrintManager}.
 */
public final class PdfPrinter {

    public interface Callback {
        void onSuccess();

        void onFailure(String message);
    }

    private PdfPrinter() {
    }

    public static void print(PrintDocumentAdapter adapter, PrintAttributes attributes,
                             ParcelFileDescriptor output, Callback callback) {
        adapter.onStart();
        adapter.onLayout(null, attributes, new CancellationSignal(), new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                adapter.onWrite(new PageRange[]{PageRange.ALL_PAGES}, output, new CancellationSignal(),
                        new PrintDocumentAdapter.WriteResultCallback() {
                            @Override
                            public void onWriteFinished(PageRange[] pages) {
                                adapter.onFinish();
                                callback.onSuccess();
                            }

                            @Override
                            public void onWriteFailed(CharSequence error) {
                                adapter.onFinish();
                                callback.onFailure(String.valueOf(error));
                            }

                            @Override
                            public void onWriteCancelled() {
                                adapter.onFinish();
                                callback.onFailure("cancelled");
                            }
                        });
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                adapter.onFinish();
                callback.onFailure(String.valueOf(error));
            }

            @Override
            public void onLayoutCancelled() {
                adapter.onFinish();
                callback.onFailure("cancelled");
            }
        }, null);
    }
}
