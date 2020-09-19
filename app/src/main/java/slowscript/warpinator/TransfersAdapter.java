package slowscript.warpinator;

import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

public class TransfersAdapter extends RecyclerView.Adapter<TransfersAdapter.ViewHolder> {

    TransfersActivity activity;

    public TransfersAdapter(TransfersActivity _app) {
        activity = _app;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View view = inflater.inflate(R.layout.transfer_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Transfer t = activity.remote.transfers.get(position);

        //ProgressBar
        holder.progressBar.setVisibility(t.status == Transfer.Status.TRANSFERRING ? View.VISIBLE : View.INVISIBLE);
        holder.progressBar.setProgress(t.getProgress());
        //Buttons
        if ((t.direction == Transfer.Direction.RECEIVE) && (t.status == Transfer.Status.WAITING_PERMISSION)) {
            holder.btnAccept.setOnClickListener((v) -> t.startReceive());
            holder.btnDecline.setOnClickListener((v) -> t.declineReceiveTransfer());
            holder.btnAccept.setVisibility(View.VISIBLE);
            holder.btnDecline.setVisibility(View.VISIBLE);
        } else {
            holder.btnAccept.setVisibility(View.INVISIBLE);
            holder.btnDecline.setVisibility(View.INVISIBLE);
        }
        holder.btnStop.setVisibility(t.status == Transfer.Status.TRANSFERRING ? View.VISIBLE : View.INVISIBLE);
        holder.btnStop.setOnClickListener((v) -> t.stop(false));
        //Main label
        String text = t.fileCount == 1 ? t.singleName: t.fileCount + " files";
        text += " (" + Formatter.formatFileSize(activity, t.totalSize) + ")";
        holder.txtTransfer.setText(text);
        //Status label
        switch (t.status) {
            case WAITING_PERMISSION:
                holder.txtStatus.setText("Waiting for permission...");
                break;
            case TRANSFERRING:
                String status = Formatter.formatFileSize(activity, t.bytesTransferred) + " / " +
                    Formatter.formatFileSize(activity, t.totalSize) + "(0 MB/s, 0s remaining)";
                holder.txtStatus.setText(status);
                break;
            default:
                holder.txtStatus.setText(t.status.toString());
        }
        //Images
        holder.imgFromTo.setImageResource(t.direction == Transfer.Direction.SEND ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_download);

    }

    @Override
    public int getItemCount() {
        return activity.remote.transfers.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        AppCompatImageButton btnAccept;
        AppCompatImageButton btnDecline;
        AppCompatImageButton btnStop;
        ImageView imgFromTo;
        ImageView imgIcon;
        TextView txtTransfer;
        TextView txtStatus;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            btnAccept = itemView.findViewById(R.id.btnAccept);
            btnDecline = itemView.findViewById(R.id.btnDecline);
            btnStop = itemView.findViewById(R.id.btnStop);
            imgFromTo = itemView.findViewById(R.id.imgFromTo);
            imgIcon = itemView.findViewById(R.id.imgIcon);
            txtStatus = itemView.findViewById(R.id.txtStatus);
            txtTransfer = itemView.findViewById(R.id.txtTransfer);
            progressBar = itemView.findViewById(R.id.progressBar);
        }
    }
}
