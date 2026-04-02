import React, { useState, useEffect } from 'react';
import { Table, Input, Card, Typography, Tag, Space } from 'antd';
import { SearchOutlined, UserOutlined } from '@ant-design/icons';

const { Title } = Typography;

const UserList = () => {
  // 1. Dữ liệu mẫu (Sau này cậu sẽ gọi API từ Backend)
  const initialData = [
    { id: '1', username: 'Nam Nguyễn', email: 'nam@gmail.com', createdAt: '2026-01-10', role: 'Viewer' },
    { id: '2', username: 'Lan Anh', email: 'lananh@yahoo.com', createdAt: '2026-02-15', role: 'Viewer' },
    { id: '3', username: 'Minh Tú', email: 'tu.minh@outlook.com', createdAt: '2026-03-01', role: 'Admin' },
    { id: '4', username: 'Hoàng Nam', email: 'hnam99@gmail.com', createdAt: '2026-03-10', role: 'Viewer' },
    { id: '5', username: 'Phương Thảo', email: 'thao.p@gmail.com', createdAt: '2026-03-20', role: 'Viewer' },
  ];

  const [users, setUsers] = useState(initialData);
  const [searchText, setSearchText] = useState('');

  // 2. Hàm xử lý tìm kiếm (vd: nhập "am" sẽ ra "Nam Nguyễn" và "Hoàng Nam")
  const handleSearch = (value) => {
    setSearchText(value);
    const filteredData = initialData.filter((user) =>
      user.username.toLowerCase().includes(value.toLowerCase())
    );
    setUsers(filteredData);
  };

  // 3. Cấu hình các cột của bảng
  const columns = [
    {
      title: 'Tên người dùng',
      dataIndex: 'username',
      key: 'username',
      render: (text) => (
        <Space>
          <UserOutlined />
          <strong>{text}</strong>
        </Space>
      ),
    },
    {
      title: 'Email',
      dataIndex: 'email',
      key: 'email',
    },
    {
      title: 'Ngày tạo tài khoản',
      dataIndex: 'createdAt',
      key: 'createdAt',
      sorter: (a, b) => new Date(a.createdAt) - new Date(b.createdAt),
    },
    {
      title: 'Vai trò',
      dataIndex: 'role',
      key: 'role',
      render: (role) => (
        <Tag color={role === 'Admin' ? 'gold' : 'blue'}>
          {role.toUpperCase()}
        </Tag>
      ),
    },
  ];

  return (
    <div style={{ padding: '24px' }}>
      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <Title level={3} style={{ margin: 0 }}>Danh sách người xem</Title>
          
          {/* Ô Search theo tên */}
          <Input.Search
            placeholder="Tìm kiếm"
            allowClear
            enterButton="Tìm kiếm"
            size="large"
            onSearch={handleSearch}
            onChange={(e) => handleSearch(e.target.value)} // Tìm kiếm ngay khi đang gõ
            style={{ width: 400 }}
            prefix={<SearchOutlined />}
          />
        </div>

        <Table
          columns={columns}
          dataSource={users}
          rowKey="id"
          pagination={{ 
            pageSize: 8,
            position:['bottomCenter']
        }}
          bordered
        />
      </Card>
    </div>
  );
};

export default UserList;