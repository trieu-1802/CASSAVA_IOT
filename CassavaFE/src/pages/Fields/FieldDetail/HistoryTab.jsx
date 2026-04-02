// src/pages/Fields/FieldDetail/HistoryTab.jsx
import React from 'react';
import { Typography, Table } from 'antd';

const { Title } = Typography;

const HistoryTab = () => {
  // Cột của bảng lịch sử tưới
  const columns = [
    {
      title: 'Người tưới',
      dataIndex: 'irrigator',
      key: 'irrigator',
    },
    {
      title: 'Thời gian tưới',
      dataIndex: 'time',
      key: 'time',
    },
    {
      title: 'Lượng nước tưới (Lít/ha)',
      dataIndex: 'waterAmount',
      key: 'waterAmount',
    },
  ];

  // Dữ liệu mẫu (Mock data)
  const data = [
    { key: '1', irrigator: 'Hệ thống tự động', time: '2026-03-29 08:00:00', waterAmount: 1500 },
    { key: '2', irrigator: 'Nguyễn Văn A', time: '2026-03-28 17:30:00', waterAmount: 1200 },
  ];

  return (
    <div style={{ padding: '16px 0' }}>
      <Title level={4}>Lịch sử tưới tiêu</Title>
      <Table 
        columns={columns} 
        dataSource={data} 
        pagination={{ pageSize: 5 }} 
        bordered
      />
    </div>
  );
};

export default HistoryTab;