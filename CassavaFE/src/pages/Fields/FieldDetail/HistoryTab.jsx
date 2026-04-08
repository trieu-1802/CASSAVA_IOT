// src/pages/Fields/FieldDetail/HistoryTab.jsx
/** 
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
*/
import React, { useState, useEffect } from 'react';
import { Typography, Table, message } from 'antd';
import dayjs from 'dayjs';
import fieldService from '../../../services/fieldService';

const { Title } = Typography;

const HistoryTab = ({ fieldId = 'fieldTest' }) => {
  const [loading, setLoading] = useState(false);
  const [dataSource, setDataSource] = useState([]);

  const fetchHistory = async () => {
    setLoading(true);
    try {
      const response = await fieldService.get(`/irrigation-history?fieldId=${fieldId}`);
      const data = response?.data || response;
      setDataSource(Array.isArray(data) ? data : []);
    } catch (error) {
      console.error("Fetch error:", error);
      message.error("Lỗi khi tải lịch sử tưới tiêu!");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHistory();
  }, [fieldId]);

  const columns = [
    {
      title: 'Người tưới',
      dataIndex: 'userName',
      key: 'userName',
      render: (text) => <b>{text === 'admin' ? 'Hệ thống' : text}</b>,
    },
    {
      title: 'Thời gian bắt đầu',
      dataIndex: 'time',
      key: 'time',
      render: (time) => dayjs(time).format('DD/MM/YYYY HH:mm:ss'),
      sorter: (a, b) => dayjs(a.time).unix() - dayjs(b.time).unix(),
    },
    {
      title: 'Lượng nước (Lít)',
      dataIndex: 'amount',
      key: 'amount',
      render: (val) => (val ? val.toFixed(2) : '0'),
    },
    {
      title: 'Thời gian tưới (Giây)',
      dataIndex: 'duration',
      key: 'duration',
      render: (ms) => (ms / 1000).toFixed(1),
    },
  ];

  return (
    <div style={{ padding: '16px 0' }}>
      <Title level={4}>Lịch sử tưới tiêu</Title>
      <Table 
        columns={columns} 
        dataSource={dataSource} 
        rowKey="id" 
        loading={loading}
        pagination={{ 
          pageSize: 5,
          placement: 'bottomCenter', 
          showLessItems: true,
          showSizeChanger: false,
          hideOnSinglePage: true,
          // GIẢI PHÁP: Ép style cho container phân trang
          style: { 
            display: 'flex', 
            justifyContent: 'center', 
            float: 'none', // Một số bản Antd cũ dùng float: right
            width: '100%' 
          } 
        }} 
        bordered
        locale={{ emptyText: 'Không có dữ liệu lịch sử' }}
      />
    </div>
  );
};

export default HistoryTab;