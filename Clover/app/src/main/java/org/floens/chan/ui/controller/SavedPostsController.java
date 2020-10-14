/*
 * Clover - 4chan browser https://github.com/Floens/Clover/
 * Copyright (C) 2014  Floens
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.floens.chan.ui.controller;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.floens.chan.R;
import org.floens.chan.controller.Controller;
import org.floens.chan.core.database.DatabaseHistoryManager;
import org.floens.chan.core.database.DatabaseManager;
import org.floens.chan.core.database.DatabaseSavedReplyManager;
import org.floens.chan.core.manager.BoardManager;
import org.floens.chan.core.model.orm.Board;
import org.floens.chan.core.model.orm.History;
import org.floens.chan.core.model.orm.SavedReply;
import org.floens.chan.core.settings.ChanSettings;
import org.floens.chan.ui.toolbar.ToolbarMenuItem;
import org.floens.chan.ui.toolbar.ToolbarMenuSubItem;
import org.floens.chan.ui.view.CrossfadeView;
import org.floens.chan.ui.view.ThumbnailView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static org.floens.chan.Chan.inject;
import static org.floens.chan.ui.theme.ThemeHelper.theme;
import static org.floens.chan.utils.AndroidUtils.dp;

public class SavedPostsController extends Controller implements
        CompoundButton.OnCheckedChangeListener,
        ToolbarNavigationController.ToolbarSearchCallback {
    private static final int SEARCH_ID = 1;

    @Inject
    DatabaseManager databaseManager;

    @Inject
    BoardManager boardManager;

    private DatabaseHistoryManager databaseHistoryManager;
    private DatabaseSavedReplyManager databaseSavedReplyManager;
    private List<History> history;

    private CrossfadeView crossfade;
    private RecyclerView recyclerView;
    private SavedPostsAdapter adapter;

    public SavedPostsController(Context context) {
        super(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inject(this);

        databaseHistoryManager = databaseManager.getDatabaseHistoryManager();
        databaseSavedReplyManager = databaseManager.getDatabaseSavedReplyManager();
        history = databaseManager.runTask(databaseHistoryManager.getHistory());

        // Navigation
        navigation.setTitle(R.string.saved_posts_screen);

        navigation.buildMenu()
                .withItem(R.drawable.ic_search_white_24dp, this::searchClicked)
                .withOverflow()
                .withSubItem(R.string.saved_reply_clear, this::clearSavedReplyClicked)
                .build().build();


        view = inflateRes(R.layout.controller_history);
        crossfade = view.findViewById(R.id.crossfade);
        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setHasFixedSize(true);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        adapter = new SavedPostsAdapter();
        recyclerView.setAdapter(adapter);
        adapter.load();

    }

    private void searchClicked(ToolbarMenuItem item) {
        ((ToolbarNavigationController) navigationController).showSearch();
    }

    private void clearHistoryClicked(ToolbarMenuSubItem item) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.history_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.history_clear_confirm_button, (dialog, which) -> {
                    databaseManager.runTaskAsync(databaseHistoryManager.clearHistory());
                    adapter.load();
                })
                .show();
    }

    private void clearSavedReplyClicked(ToolbarMenuSubItem item) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.saved_reply_clear_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.saved_reply_clear_confirm_button, (dialog, which) ->
                        databaseManager.runTaskAsync(databaseSavedReplyManager.clearSavedReplies()))
                .show();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        ChanSettings.historyEnabled.set(isChecked);
    }

    private void openThread(History history) {
        ViewThreadController viewThreadController = new ViewThreadController(context);
        viewThreadController.setLoadable(history.loadable);
        navigationController.pushController(viewThreadController);
    }

    private void deleteHistory(History history) {
        databaseManager.runTask(databaseHistoryManager.removeHistory(history));
        adapter.load();
    }

    @Override
    public void onSearchVisibilityChanged(boolean visible) {
        if (!visible) {
            adapter.search(null);
        }
    }

    @Override
    public void onSearchEntered(String entered) {
        adapter.search(entered);
    }

    private class SavedPostsAdapter extends RecyclerView.Adapter<PostCell> implements DatabaseManager.TaskResult<List<SavedReply>> {
        private List<SavedReply> sourceList = new ArrayList<>();
        private List<SavedReply> displayList = new ArrayList<>();
        private String searchQuery;

        private boolean resultPending = false;

        public SavedPostsAdapter() {
            setHasStableIds(true);
        }

        @Override
        public PostCell onCreateViewHolder(ViewGroup parent, int viewType) {
            return new PostCell(LayoutInflater.from(parent.getContext()).inflate(R.layout.cell_history, parent, false));
        }

        @Override
        public void onBindViewHolder(PostCell holder, int position) {
            SavedReply savedReply = displayList.get(position);

            for (History historyItem : history) {
                if (savedReply.no == historyItem.loadable.no) {
                    holder.thumbnail.setUrl(historyItem.thumbnailUrl, dp(48), dp(48));
                }
            }

            holder.text.setText(String.valueOf(savedReply.no));
            holder.subtext.setText(savedReply.board);
        }

        @Override
        public int getItemCount() {
            return displayList.size();
        }

        //@Override
        //public long getItemId(int position) {
            //return displayList.get(position).id;
        //}

        public void search(String query) {
            this.searchQuery = query;
            filter();
        }

        private void load() {
            if (!resultPending) {
                resultPending = true;
                databaseManager.runTaskAsync(databaseSavedReplyManager.getSavedReplies(), this);
            }
        }

        @Override
        public void onComplete(List<SavedReply> result) {
            resultPending = false;
            sourceList.clear();
            sourceList.addAll(result);
            crossfade.toggle(!sourceList.isEmpty(), true);
            filter();
        }

        private void filter() {
            displayList.clear();
            displayList.addAll(sourceList);
            notifyDataSetChanged();
        }
    }

    private class PostCell extends RecyclerView.ViewHolder implements View.OnClickListener {
        private ThumbnailView thumbnail;
        private TextView text;
        private TextView subtext;
        private ImageView delete;

        public PostCell(View itemView) {
            super(itemView);

            thumbnail = itemView.findViewById(R.id.thumbnail);
            thumbnail.setCircular(true);
            text = itemView.findViewById(R.id.text);
            subtext = itemView.findViewById(R.id.subtext);
            delete = itemView.findViewById(R.id.delete);

            theme().clearDrawable.apply(delete);

            delete.setOnClickListener(this);

            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
        }
    }
}
