package com.example.demo.repositories;

import com.google.firebase.database.*;
import org.springframework.stereotype.Repository;


@Repository
public class FirebaseRepository {
    private DatabaseReference database;
    public FirebaseRepository() {
        // Không khởi tạo database ở đây
    }

    private DatabaseReference getDatabase() {
        if (database == null) {
            database = FirebaseDatabase.getInstance().getReference();
        }
        return database;
    }

    public void readData(String node, ValueEventListener listener) {
        DatabaseReference nodeRef = getDatabase().child(node);
        nodeRef.addValueEventListener(listener);
    }
}
