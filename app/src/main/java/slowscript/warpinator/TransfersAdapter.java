package slowscript.warpinator;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

public class TransfersAdapter extends RecyclerView.Adapter<TransfersAdapter.ViewHolder> {

    MainActivity app;

    public TransfersAdapter(MainActivity _app) {
        app = _app;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(app);
        View view = inflater.inflate(R.layout.remote_view, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Remote r = (Remote) app.remotes.values().toArray()[position];
        holder.txtName.setText(r.displayName);
        holder.txtUsername.setText(r.userName + "@" + r.hostname);

        holder.cardView.setOnClickListener((view) -> {
            Intent i = new Intent(app, TransfersActivity.class);
            i.putExtra("index", position);
            app.startActivity(i);
        });
    }

    @Override
    public int getItemCount() {
        return app.remotes.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder{

        CardView cardView;
        TextView txtName;
        TextView txtUsername;
        ImageView imgProfile;
        ImageView imgStatus;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = itemView.findViewById(R.id.cardView);
            txtName = itemView.findViewById(R.id.txtName);
            txtUsername = itemView.findViewById(R.id.txtUsername);
            imgProfile = itemView.findViewById(R.id.imgProfile);
            imgStatus = itemView.findViewById(R.id.imgStatus);
        }
    }
}
