package com.nordicid.rfiddemo;

import java.util.HashMap;

import android.app.AlertDialog;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.Button;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import com.nordicid.controllers.InventoryController;

public class InventoryAppFoundTab extends Fragment {

	public SimpleAdapter mFoundTagsListViewAdapter;
	private ListView mInventoryTagList;
	private InventoryController mIC;

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		return inflater.inflate(R.layout.tab_inventory_taglist, container, false);
	}
	
	
	@Override
	public void onViewCreated(View view, Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		
		mInventoryTagList = (ListView) view.findViewById(R.id.tags_listview);
		mIC = InventoryAppTabbed.getInstance().getInventoryController();

		mFoundTagsListViewAdapter = new SimpleAdapter(
				getActivity(),
				mIC.getListViewAdapterData(),
				R.layout.taglist_row, new String[] {"epc","rssi"}, new int[] {R.id.tagText,R.id.rssiText});


		mInventoryTagList.setEmptyView(view.findViewById(R.id.no_tags));
		mInventoryTagList.setAdapter(mFoundTagsListViewAdapter);
		mInventoryTagList.setCacheColorHint(0);
		mInventoryTagList.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

				@SuppressWarnings("unchecked")
				final HashMap<String, String> selectedTagData = (HashMap<String, String>) mInventoryTagList.getItemAtPosition(position);
				mIC.showTagDialog(getActivity(), selectedTagData);
			}

		});

		mFoundTagsListViewAdapter.notifyDataSetChanged();
	}
}
